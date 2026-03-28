package org.finos.gitproxy.jetty;

import jakarta.servlet.DispatcherType;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jgit.http.server.GitServlet;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.config.GpgConfig;
import org.finos.gitproxy.config.InMemoryProviderConfigurationSource;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.git.*;
import org.finos.gitproxy.jetty.api.ObjectMapperProvider;
import org.finos.gitproxy.jetty.api.PushRecordResource;
import org.finos.gitproxy.jetty.config.JettyConfigurationBuilder;
import org.finos.gitproxy.jetty.config.JettyConfigurationLoader;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.service.DummyUserAuthorizationService;
import org.finos.gitproxy.servlet.GitProxyServlet;
import org.finos.gitproxy.servlet.filter.*;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;

/**
 * Standalone Jetty server application for the JGit proxy. Registers two servlets per provider:
 *
 * <ul>
 *   <li><b>GitServlet</b> on {@code /push/...} (e.g. {@code /push/github.com/*}) — store-and-forward mode using JGit's
 *       native ReceivePack/UploadPack stack with sideband validation feedback
 *   <li><b>GitProxyServlet</b> on {@code /proxy/...} (e.g. {@code /proxy/github.com/*}) — transparent HTTP proxy bypass
 * </ul>
 *
 * <p>Configuration is loaded from {@code git-proxy.yml} and {@code git-proxy-local.yml}, overridable with
 * {@code GITPROXY_} environment variables. See {@link JettyConfigurationLoader} for details.
 */
@Slf4j
public class GitProxyJettyApplication {

    private static final String PUSH_PATH_PREFIX = "/push";
    private static final String PROXY_PATH_PREFIX = "/proxy";

    public static void main(String[] args) throws Exception {
        log.info("Starting JGit Proxy Jetty Application...");
        writePidFile();

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

        // Initialize push store (database for audit records)
        PushStore pushStore = configBuilder.buildPushStore();
        log.info("Push store initialized: {}", pushStore.getClass().getSimpleName());

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

        // Shared commit validation config for both proxy filters and store-and-forward hooks
        var commitConfig = buildCommitConfig();

        // Register servlets and filters for each provider
        for (GitProxyProvider provider : providerConfig.getProviders()) {
            log.info("Registering provider: {}", provider.getName());

            // PRIMARY: GitServlet for store-and-forward on the main path
            registerGitServlet(context, provider, storeForwardCache, commitConfig, pushStore);

            // SECONDARY: Proxy servlet on /proxy prefix for bypass/debugging
            registerProxyServlet(context, provider);
            registerFilters(context, provider, proxyCache, configBuilder, commitConfig, pushStore);
        }

        // REST API servlet (Jersey JAX-RS) — registers alongside JGit servlets on /api/*
        registerApiServlet(context, pushStore);

        server.setHandler(context);
        server.start();

        log.info("JGit Proxy Jetty server started on port {}", connector.getPort());
        log.info("Available providers:");
        for (GitProxyProvider provider : providerConfig.getProviders()) {
            log.info(
                    "  - {} (store-and-forward) at {}{}",
                    provider.getName(),
                    PUSH_PATH_PREFIX,
                    provider.servletMapping());
            log.info("  - {} (proxy bypass) at {}{}", provider.getName(), PROXY_PATH_PREFIX, provider.servletMapping());
        }

        server.join();
    }

    private static void registerApiServlet(ServletContextHandler context, PushStore pushStore) {
        ResourceConfig config = new ResourceConfig();
        config.register(PushRecordResource.class);
        config.register(ObjectMapperProvider.class);
        config.register(JacksonFeature.class);
        config.register(new AbstractBinder() {
            @Override
            protected void configure() {
                bind(pushStore).to(PushStore.class);
            }
        });

        var apiServlet = new ServletHolder(new ServletContainer(config));
        apiServlet.setName("api");
        context.addServlet(apiServlet, "/api/*");
        log.info("Registered REST API servlet at /api/*");
    }

    private static void registerGitServlet(
            ServletContextHandler context,
            GitProxyProvider provider,
            LocalRepositoryCache cache,
            CommitConfig commitConfig,
            PushStore pushStore) {
        var resolver = new StoreAndForwardRepositoryResolver(cache, provider);

        var gitServlet = new GitServlet();
        gitServlet.setRepositoryResolver(resolver);
        gitServlet.setReceivePackFactory(new StoreAndForwardReceivePackFactory(provider, commitConfig, pushStore));
        gitServlet.setUploadPackFactory(new StoreAndForwardUploadPackFactory());

        String pushPath = PUSH_PATH_PREFIX + provider.servletPath();
        String pushMapping = pushPath + "/*";

        var holder = new ServletHolder(gitServlet);
        holder.setName("git-" + provider.getName());
        context.addServlet(holder, pushMapping);

        // Convert plain HTTP errors to git smart protocol errors so clients see the message
        var errorFilter = new FilterHolder(new SmartHttpErrorFilter());
        context.addFilter(errorFilter, pushMapping, EnumSet.of(DispatcherType.REQUEST));

        // Challenge unauthenticated requests so git clients send credentials
        var authFilter = new FilterHolder(new BasicAuthChallengeFilter());
        context.addFilter(authFilter, pushMapping, EnumSet.of(DispatcherType.REQUEST));

        log.info("Registered GitServlet for {} at {}", provider.getName(), pushMapping);
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
            JettyConfigurationBuilder configBuilder,
            CommitConfig commitConfig,
            PushStore pushStore) {
        String urlPattern = PROXY_PATH_PREFIX + provider.servletPath() + "/*";

        // Push store audit filter — wraps entire chain via try-finally so it always persists
        var pushStoreAuditFilter = new PushStoreAuditFilter(pushStore);
        var pushStoreAuditFilterHolder = new FilterHolder(pushStoreAuditFilter);
        pushStoreAuditFilterHolder.setAsyncSupported(true);
        context.addFilter(pushStoreAuditFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        // Force Git client filter (must be first)
        var forceGitClientFilter = new ForceGitClientFilter();
        var forceGitClientFilterHolder = new FilterHolder(forceGitClientFilter);
        forceGitClientFilterHolder.setAsyncSupported(true);
        context.addFilter(forceGitClientFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        // Parse git request filter
        var parseRequestFilter = new ParseGitRequestFilter(provider, PROXY_PATH_PREFIX);
        var parseRequestFilterHolder = new FilterHolder(parseRequestFilter);
        parseRequestFilterHolder.setAsyncSupported(true);
        context.addFilter(parseRequestFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        // Enrich push commits filter (uses JGit to get full commit info)
        var enrichCommitsFilter = new EnrichPushCommitsFilter(provider, repositoryCache, PROXY_PATH_PREFIX);
        var enrichCommitsFilterHolder = new FilterHolder(enrichCommitsFilter);
        enrichCommitsFilterHolder.setAsyncSupported(true);
        context.addFilter(enrichCommitsFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        // Whitelist filters from configuration
        List<WhitelistByUrlFilter> whitelistFilters = configBuilder.buildWhitelistFilters(provider);
        if (!whitelistFilters.isEmpty()) {
            var whitelistAggregateFilter =
                    new WhitelistAggregateFilter(1000, provider, whitelistFilters, PROXY_PATH_PREFIX);
            var whitelistAggFilterHolder = new FilterHolder(whitelistAggregateFilter);
            whitelistAggFilterHolder.setAsyncSupported(true);
            context.addFilter(whitelistAggFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));
            log.info("Registered {} whitelist filter(s) for provider {}", whitelistFilters.size(), provider.getName());
        }

        // User push permission check (order 2000)
        var pushPermissionFilter = new CheckUserPushPermissionFilter(new DummyUserAuthorizationService());
        var pushPermissionFilterHolder = new FilterHolder(pushPermissionFilter);
        pushPermissionFilterHolder.setAsyncSupported(true);
        context.addFilter(pushPermissionFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        // Author email validation (order 2100)
        var authorEmailsFilter = new CheckAuthorEmailsFilter(commitConfig);
        var authorEmailsFilterHolder = new FilterHolder(authorEmailsFilter);
        authorEmailsFilterHolder.setAsyncSupported(true);
        context.addFilter(authorEmailsFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        // Commit message validation (order 2200)
        var commitMessagesFilter = new CheckCommitMessagesFilter(commitConfig);
        var commitMessagesFilterHolder = new FilterHolder(commitMessagesFilter);
        commitMessagesFilterHolder.setAsyncSupported(true);
        context.addFilter(commitMessagesFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        // GPG signature validation (order 2300) — disabled by default, logs when checked
        var gpgFilter = new GpgSignatureFilter(GpgConfig.defaultConfig());
        var gpgFilterHolder = new FilterHolder(gpgFilter);
        gpgFilterHolder.setAsyncSupported(true);
        context.addFilter(gpgFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        log.info("Registered content validation filters for provider {}", provider.getName());

        // Audit log filter (should be last)
        var auditFilter = new AuditLogFilter();
        var auditFilterHolder = new FilterHolder(auditFilter);
        auditFilterHolder.setAsyncSupported(true);
        context.addFilter(auditFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));
    }

    /**
     * Build commit validation config with restrictions:
     *
     * <ul>
     *   <li>Author email domain must match known providers (proton.me, gmail.com, etc.)
     *   <li>Block noreply/bot email local parts
     *   <li>Block WIP/fixup/squash commit messages
     *   <li>Block commit messages containing password/secret/token patterns
     * </ul>
     */
    private static CommitConfig buildCommitConfig() {
        return CommitConfig.builder()
                .author(CommitConfig.AuthorConfig.builder()
                        .email(CommitConfig.EmailConfig.builder()
                                .domain(CommitConfig.DomainConfig.builder()
                                        .allow(Pattern.compile(
                                                "(proton\\.me|gmail\\.com|outlook\\.com|yahoo\\.com|example\\.com)$"))
                                        .build())
                                .local(CommitConfig.LocalConfig.builder()
                                        .block(Pattern.compile("^(noreply|no-reply|bot|nobody)$"))
                                        .build())
                                .build())
                        .build())
                .message(CommitConfig.MessageConfig.builder()
                        .block(CommitConfig.BlockConfig.builder()
                                .literals(List.of("WIP", "DO NOT MERGE", "fixup!", "squash!"))
                                .patterns(List.of(Pattern.compile("(?i)(password|secret|token)\\s*[=:]\\s*\\S+")))
                                .build())
                        .build())
                .build();
    }

    /** Write PID file so {@code ./gradlew :jgit-proxy-jetty:stop} can find and kill this process. */
    private static void writePidFile() {
        String pidFilePath = System.getProperty("jgitproxy.pidfile");
        if (pidFilePath == null) {
            return;
        }
        try {
            var pidFile = java.nio.file.Path.of(pidFilePath);
            java.nio.file.Files.createDirectories(pidFile.getParent());
            java.nio.file.Files.writeString(
                    pidFile, String.valueOf(ProcessHandle.current().pid()));
            pidFile.toFile().deleteOnExit();
            log.info("Wrote PID file: {}", pidFilePath);
        } catch (Exception e) {
            log.warn("Could not write PID file: {}", e.getMessage());
        }
    }
}
