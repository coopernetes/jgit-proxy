package org.finos.gitproxy.jetty;

import jakarta.servlet.DispatcherType;
import java.util.EnumSet;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.finos.gitproxy.config.InMemoryProviderConfigurationSource;
import org.finos.gitproxy.git.LocalRepositoryCache;
import org.finos.gitproxy.jetty.config.JettyConfigurationBuilder;
import org.finos.gitproxy.jetty.config.JettyConfigurationLoader;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.servlet.GitProxyServlet;
import org.finos.gitproxy.servlet.filter.*;

/**
 * Standalone Jetty server application for the JGit proxy. This application uses YAML-based configuration loaded from
 * {@code git-proxy.yml} and {@code git-proxy-local.yml} to dynamically configure providers and filters.
 *
 * <p>Configuration can be overridden using environment variables with the {@code GITPROXY_} prefix. See
 * {@link JettyConfigurationLoader} for details.
 */
@Slf4j
public class GitProxyJettyApplication {

    public static void main(String[] args) throws Exception {
        log.info("Starting JGit Proxy Jetty Application...");

        // Load configuration from YAML files and environment variables
        var configLoader = new JettyConfigurationLoader();
        var configBuilder = new JettyConfigurationBuilder(configLoader);

        // Create thread pool for the server
        var threadPool = new QueuedThreadPool();
        threadPool.setName("jgit-proxy-server");

        // Create the Jetty server
        var server = new Server(threadPool);

        // Configure connector using configured port
        var connector = new ServerConnector(server);
        connector.setPort(configBuilder.getServerPort());
        server.addConnector(connector);

        // Initialize the local repository cache
        var repositoryCache = new LocalRepositoryCache();
        log.info("Initialized LocalRepositoryCache");

        // Configure providers from YAML configuration
        List<GitProxyProvider> providers = configBuilder.buildProviders();
        var providerConfig = new InMemoryProviderConfigurationSource(providers);

        // Create servlet context
        var context = new ServletContextHandler("/", false, false);

        // Register servlets and filters for each provider
        for (GitProxyProvider provider : providerConfig.getProviders()) {
            log.info("Registering proxy for provider: {}", provider.getName());
            registerProxyServlet(context, provider);
            registerFilters(context, provider, repositoryCache, configBuilder);
        }

        server.setHandler(context);
        server.start();

        log.info("JGit Proxy Jetty server started on port {}", connector.getPort());
        log.info("Available providers:");
        for (GitProxyProvider provider : providerConfig.getProviders()) {
            log.info("  - {} at {}", provider.getName(), provider.servletMapping());
        }

        server.join();
    }

    private static void registerProxyServlet(ServletContextHandler context, GitProxyProvider provider) {
        var proxyServlet = new GitProxyServlet();
        var proxyServletHolder = new ServletHolder(proxyServlet);
        proxyServletHolder.setInitParameter("proxyTo", provider.getUri().toString());
        proxyServletHolder.setInitParameter("prefix", provider.servletPath());
        proxyServletHolder.setInitParameter("hostHeader", provider.getUri().getHost());
        proxyServletHolder.setInitParameter("preserveHost", "false");
        context.addServlet(proxyServletHolder, provider.servletMapping());
    }

    private static void registerFilters(
            ServletContextHandler context,
            GitProxyProvider provider,
            LocalRepositoryCache repositoryCache,
            JettyConfigurationBuilder configBuilder) {
        String urlPattern = provider.servletMapping();

        // Force Git client filter (must be first)
        var forceGitClientFilter = new ForceGitClientFilter();
        var forceGitClientFilterHolder = new FilterHolder(forceGitClientFilter);
        forceGitClientFilterHolder.setAsyncSupported(true);
        context.addFilter(forceGitClientFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        // Parse git request filter
        var parseRequestFilter = new ParseGitRequestFilter(provider);
        var parseRequestFilterHolder = new FilterHolder(parseRequestFilter);
        parseRequestFilterHolder.setAsyncSupported(true);
        context.addFilter(parseRequestFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        // Enrich push commits filter (uses JGit to get full commit info)
        var enrichCommitsFilter = new EnrichPushCommitsFilter(provider, repositoryCache);
        var enrichCommitsFilterHolder = new FilterHolder(enrichCommitsFilter);
        enrichCommitsFilterHolder.setAsyncSupported(true);
        context.addFilter(enrichCommitsFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        // Whitelist filters from configuration
        List<WhitelistByUrlFilter> whitelistFilters = configBuilder.buildWhitelistFilters(provider);
        if (!whitelistFilters.isEmpty()) {
            var whitelistAggregateFilter = new WhitelistAggregateFilter(1000, provider, whitelistFilters);
            var whitelistAggFilterHolder = new FilterHolder(whitelistAggregateFilter);
            whitelistAggFilterHolder.setAsyncSupported(true);
            context.addFilter(whitelistAggFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));
            log.info("Registered {} whitelist filter(s) for provider {}", whitelistFilters.size(), provider.getName());
        }

        // Audit filter (should be last)
        var auditFilter = new AuditLogFilter();
        var auditFilterHolder = new FilterHolder(auditFilter);
        auditFilterHolder.setAsyncSupported(true);
        context.addFilter(auditFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));
    }
}
