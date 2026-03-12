package org.finos.gitproxy.jetty;

import jakarta.servlet.DispatcherType;
import java.util.ArrayList;
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
import org.finos.gitproxy.provider.BitbucketProvider;
import org.finos.gitproxy.provider.GitHubProvider;
import org.finos.gitproxy.provider.GitLabProvider;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.servlet.GitProxyServlet;
import org.finos.gitproxy.servlet.filter.*;

/**
 * Standalone Jetty server application for the JGit proxy. This application uses the core module's reusable servlet and
 * filter code to create a standalone proxy server without Spring dependencies.
 */
@Slf4j
public class GitProxyJettyApplication {

    public static void main(String[] args) throws Exception {
        log.info("Starting JGit Proxy Jetty Application...");

        // Create thread pool for the server
        var threadPool = new QueuedThreadPool();
        threadPool.setName("jgit-proxy-server");

        // Create the Jetty server
        var server = new Server(threadPool);

        // Configure connector
        var connector = new ServerConnector(server);
        connector.setPort(getPort());
        server.addConnector(connector);

        // Initialize the local repository cache
        var repositoryCache = new LocalRepositoryCache();
        log.info("Initialized LocalRepositoryCache");

        // Configure providers
        List<GitProxyProvider> providers = createProviders();
        var providerConfig = new InMemoryProviderConfigurationSource(providers);

        // Create servlet context
        var context = new ServletContextHandler("/", false, false);

        // Register servlets and filters for each provider
        for (GitProxyProvider provider : providerConfig.getProviders()) {
            log.info("Registering proxy for provider: {}", provider.getName());
            registerProxyServlet(context, provider);
            registerFilters(context, provider, repositoryCache);
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

    private static int getPort() {
        String portStr = System.getProperty("port", System.getenv().getOrDefault("PORT", "8080"));
        return Integer.parseInt(portStr);
    }

    private static List<GitProxyProvider> createProviders() {
        List<GitProxyProvider> providers = new ArrayList<>();

        // Add default providers
        providers.add(new GitHubProvider(""));
        providers.add(new GitLabProvider(""));
        providers.add(new BitbucketProvider(""));

        return providers;
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
            ServletContextHandler context, GitProxyProvider provider, LocalRepositoryCache repositoryCache) {
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

        // Example whitelist filter (can be configured based on requirements)
        var whitelistFilters = List.of(new WhitelistByUrlFilter(
                1100, provider, List.of("finos/git-proxy", "coopernetes/test-repo"), RepositoryUrlFilter.Target.SLUG));
        var whitelistAggregateFilter = new WhitelistAggregateFilter(1000, provider, whitelistFilters);
        var whitelistAggFilterHolder = new FilterHolder(whitelistAggregateFilter);
        whitelistAggFilterHolder.setAsyncSupported(true);
        context.addFilter(whitelistAggFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        // Audit filter (should be last)
        var auditFilter = new AuditLogFilter();
        var auditFilterHolder = new FilterHolder(auditFilter);
        auditFilterHolder.setAsyncSupported(true);
        context.addFilter(auditFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));
    }
}
