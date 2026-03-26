package org.finos.gitproxy.jetty;

import jakarta.servlet.DispatcherType;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jgit.http.server.GitServlet;
import org.finos.gitproxy.config.InMemoryProviderConfigurationSource;
import org.finos.gitproxy.git.*;
import org.finos.gitproxy.jetty.config.JettyConfigurationBuilder;
import org.finos.gitproxy.jetty.config.JettyConfigurationLoader;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.servlet.GitProxyServlet;
import org.finos.gitproxy.servlet.filter.*;

/**
 * Standalone Jetty server application for the JGit proxy. Registers two servlets per provider:
 *
 * <ul>
 *   <li><b>GitServlet</b> on the primary path (e.g. {@code /github.com/*}) — store-and-forward mode using JGit's native
 *       ReceivePack/UploadPack stack
 *   <li><b>GitProxyServlet</b> on {@code /proxy/...} (e.g. {@code /proxy/github.com/*}) — transparent HTTP proxy bypass
 * </ul>
 *
 * <p>Configuration is loaded from {@code git-proxy.yml} and {@code git-proxy-local.yml}, overridable with
 * {@code GITPROXY_} environment variables. See {@link JettyConfigurationLoader} for details.
 */
@Slf4j
public class GitProxyJettyApplication {

    private static final String PROXY_PATH_PREFIX = "/proxy";

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

        // Store-and-forward cache: full clone (depth=0) required for ReceivePack
        var storeForwardCache = new LocalRepositoryCache(Files.createTempDirectory("jgit-proxy-sf-"), 0, true);
        log.info("Initialized store-and-forward LocalRepositoryCache (full clone)");

        // Proxy filter cache: shallow clone for commit inspection
        var proxyCache = new LocalRepositoryCache();
        log.info("Initialized proxy LocalRepositoryCache (shallow clone)");

        // Configure providers from YAML configuration
        List<GitProxyProvider> providers = configBuilder.buildProviders();
        var providerConfig = new InMemoryProviderConfigurationSource(providers);

        // Create servlet context
        var context = new ServletContextHandler("/", false, false);

        // Register servlets and filters for each provider
        for (GitProxyProvider provider : providerConfig.getProviders()) {
            log.info("Registering provider: {}", provider.getName());

            // PRIMARY: GitServlet for store-and-forward on the main path
            registerGitServlet(context, provider, storeForwardCache);

            // SECONDARY: Proxy servlet on /proxy prefix for bypass/debugging
            registerProxyServlet(context, provider);
            registerFilters(context, provider, proxyCache, configBuilder);
        }

        server.setHandler(context);
        server.start();

        log.info("JGit Proxy Jetty server started on port {}", connector.getPort());
        log.info("Available providers:");
        for (GitProxyProvider provider : providerConfig.getProviders()) {
            log.info("  - {} (store-and-forward) at {}", provider.getName(), provider.servletMapping());
            log.info("  - {} (proxy bypass) at {}{}", provider.getName(), PROXY_PATH_PREFIX, provider.servletMapping());
        }

        server.join();
    }

    private static void registerGitServlet(
            ServletContextHandler context, GitProxyProvider provider, LocalRepositoryCache cache) {
        var resolver = new StoreAndForwardRepositoryResolver(cache, provider);

        var gitServlet = new GitServlet();
        gitServlet.setRepositoryResolver(resolver);
        gitServlet.setReceivePackFactory(new StoreAndForwardReceivePackFactory(provider));
        gitServlet.setUploadPackFactory(new StoreAndForwardUploadPackFactory());

        var holder = new ServletHolder(gitServlet);
        holder.setName("git-" + provider.getName());
        context.addServlet(holder, provider.servletMapping());

        // Challenge unauthenticated requests so git clients send credentials
        var authFilter = new FilterHolder(new BasicAuthChallengeFilter());
        context.addFilter(authFilter, provider.servletMapping(), EnumSet.of(DispatcherType.REQUEST));

        log.info("Registered GitServlet for {} at {}", provider.getName(), provider.servletMapping());
    }

    private static void registerProxyServlet(ServletContextHandler context, GitProxyProvider provider) {
        String proxyPath = PROXY_PATH_PREFIX + provider.servletPath();
        String proxyMapping = proxyPath + "/*";

        var proxyServlet = new GitProxyServlet();
        var proxyServletHolder = new ServletHolder(proxyServlet);
        proxyServletHolder.setName("proxy-" + provider.getName());
        proxyServletHolder.setInitParameter("proxyTo", provider.getUri().toString());
        proxyServletHolder.setInitParameter("prefix", proxyPath);
        proxyServletHolder.setInitParameter("hostHeader", provider.getUri().getHost());
        proxyServletHolder.setInitParameter("preserveHost", "false");
        context.addServlet(proxyServletHolder, proxyMapping);

        log.info("Registered proxy servlet for {} at {}", provider.getName(), proxyMapping);
    }

    private static void registerFilters(
            ServletContextHandler context,
            GitProxyProvider provider,
            LocalRepositoryCache repositoryCache,
            JettyConfigurationBuilder configBuilder) {
        String urlPattern = PROXY_PATH_PREFIX + provider.servletPath() + "/*";

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
