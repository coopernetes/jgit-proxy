package org.finos.gitproxy.dashboard;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import java.util.EnumSet;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.ee11.servlet.FilterHolder;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.finos.gitproxy.approval.UiApprovalGateway;
import org.finos.gitproxy.db.UrlRuleRegistry;
import org.finos.gitproxy.jetty.GitProxyContext;
import org.finos.gitproxy.jetty.GitProxyJettyApplication;
import org.finos.gitproxy.jetty.GitProxyServletRegistrar;
import org.finos.gitproxy.jetty.config.GitProxyConfig;
import org.finos.gitproxy.jetty.config.GitProxyConfigLoader;
import org.finos.gitproxy.jetty.config.JettyConfigurationBuilder;
import org.finos.gitproxy.jetty.reload.ConfigHolder;
import org.finos.gitproxy.jetty.reload.LiveConfigLoader;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.provider.InMemoryProviderRegistry;
import org.finos.gitproxy.provider.ProviderRegistry;
import org.springframework.security.web.context.AbstractSecurityWebApplicationInitializer;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Jetty application that runs the git proxy server together with the Spring MVC dashboard and REST API. This is the
 * entry point to use when you want the approval workflow UI alongside the proxy.
 *
 * <p>For a proxy-only deployment (no UI, no REST API), use {@code GitProxyJettyApplication} in
 * {@code git-proxy-java-server} instead.
 *
 * <p>Git servlets are registered at {@code /push/*} and {@code /proxy/*}. Spring's DispatcherServlet is registered at
 * {@code /*} - the more-specific git paths take precedence per the servlet spec, so Spring only handles {@code /api/*},
 * {@code /}, and static assets.
 */
@Slf4j
public class GitProxyWithDashboardApplication {

    public static void main(String[] args) throws Exception {
        log.info("Starting JGit Proxy with Dashboard...");
        GitProxyJettyApplication.writePidFile();

        GitProxyConfig gitProxyConfig = GitProxyConfigLoader.load();
        var configBuilder = new JettyConfigurationBuilder(gitProxyConfig);
        configBuilder.validateProviderReferences(); // fail fast before any DB or port setup

        var threadPool = new QueuedThreadPool();
        threadPool.setName("git-proxy-java-dashboard");

        var server = new Server(threadPool);
        var connector = new ServerConnector(server);
        connector.setPort(configBuilder.getServerPort());
        server.addConnector(connector);

        // Graceful shutdown: drain in-flight requests for up to 30s on SIGTERM before the JVM exits.
        // Without this, rolling deploys on Kubernetes/OCP hard-kill active git push/proxy streams.
        server.setStopTimeout(30_000);
        server.setStopAtShutdown(true);

        var tls = configBuilder.getTlsConfig();
        if (tls.isServerTlsConfigured()) {
            server.addConnector(GitProxyJettyApplication.buildHttpsConnector(server, tls));
            log.info("HTTPS listener configured on port {}", tls.getPort());
        }

        // buildPushStore() is called first so we can hand the same instance to UiApprovalGateway.
        // buildProxyContext() will reuse the cached instance internally.
        var pushStore = configBuilder.buildPushStore();
        log.info("Push store initialized: {}", pushStore.getClass().getSimpleName());

        UrlRuleRegistry urlRuleRegistry = configBuilder.buildUrlRuleRegistry();

        // Always use UiApprovalGateway when running with the dashboard — the REST API is what drives approval.
        // This is intentionally not derived from approval-mode config: the dashboard deployment always needs
        // UI-based review regardless of what is set in the config file.
        GitProxyContext ctx = configBuilder.buildProxyContext(new UiApprovalGateway(pushStore));

        List<GitProxyProvider> providers = configBuilder.buildProviders();
        var providerConfig = new InMemoryProviderRegistry(providers);

        var context = new ServletContextHandler("/", true, false);

        // Register git proxy servlets (store-and-forward + transparent proxy) for each provider
        GitProxyServletRegistrar.registerProviders(context, ctx, configBuilder, providers);

        ConfigHolder configHolder = configBuilder.buildConfigHolder();
        var liveConfigLoader = new LiveConfigLoader(
                configHolder,
                gitProxyConfig,
                configBuilder.getReloadConfig(),
                ctx.urlRuleRegistry(),
                ctx.repoPermissionService());
        liveConfigLoader.start();
        server.addEventListener(new LifeCycle.Listener() {
            @Override
            public void lifeCycleStopping(LifeCycle event) {
                liveConfigLoader.stop();
            }
        });

        // Spring MVC DispatcherServlet at /* - git-specific paths take precedence per servlet spec
        var jdbcDataSource = configBuilder.getJdbcDataSourceOrNull();
        var mongoFactory = configBuilder.getMongoStoreFactoryOrNull();
        registerSpringServlet(
                context,
                ctx,
                providerConfig,
                gitProxyConfig,
                configHolder,
                liveConfigLoader,
                urlRuleRegistry,
                jdbcDataSource,
                mongoFactory);

        server.setHandler(context);
        server.start();

        log.info("JGit Proxy with Dashboard started on port {}", connector.getPort());
        log.info("  Dashboard:  http://localhost:{}/dashboard/", connector.getPort());
        log.info("  API:        http://localhost:{}/api", connector.getPort());
        log.info("  Health:     http://localhost:{}/api/health", connector.getPort());
        log.info("  Swagger UI: http://localhost:{}/swagger-ui", connector.getPort());

        server.join();
    }

    private static void registerSpringServlet(
            ServletContextHandler context,
            GitProxyContext ctx,
            ProviderRegistry providers,
            GitProxyConfig gitProxyConfig,
            ConfigHolder configHolder,
            LiveConfigLoader liveConfigLoader,
            UrlRuleRegistry urlRuleRegistry,
            javax.sql.DataSource jdbcDataSource,
            org.finos.gitproxy.db.MongoStoreFactory mongoFactory) {
        var appContext = new AnnotationConfigWebApplicationContext();
        appContext.register(SpringWebConfig.class, SecurityConfig.class, SessionStoreConfig.class);
        appContext.addBeanFactoryPostProcessor(bf -> {
            bf.registerSingleton("pushStore", ctx.pushStore());
            bf.registerSingleton("providers", providers);
            bf.registerSingleton("userStore", ctx.userStore());
            bf.registerSingleton("gitProxyConfig", gitProxyConfig);
            bf.registerSingleton("configHolder", configHolder);
            bf.registerSingleton("liveConfigLoader", liveConfigLoader);
            bf.registerSingleton("repoRegistry", urlRuleRegistry);
            bf.registerSingleton("fetchStore", ctx.fetchStore());
            if (ctx.repoPermissionService() != null) {
                bf.registerSingleton("repoPermissionService", ctx.repoPermissionService());
            }
            // Expose the JDBC DataSource as a Spring bean so SessionStoreConfig can inject it
            // when session-store=jdbc. Null for MongoDB deployments (no JDBC DataSource available).
            if (jdbcDataSource != null) {
                bf.registerSingleton("dataSource", jdbcDataSource);
            }
            // Expose the shared MongoClient + database name for session-store=mongo. Null for JDBC deployments.
            if (mongoFactory != null) {
                bf.registerSingleton("mongoClient", mongoFactory.getMongoClient());
                bf.registerSingleton("mongoDatabaseName", mongoFactory.getDatabaseName());
            }
        });

        // Refresh the Spring context inside a ServletContextListener so the ServletContext is set
        // before any beans that require it (e.g. resource handlers) are instantiated.
        // Servlet spec guarantees: listeners fire → filters init → servlets init.
        // This ensures DelegatingFilterProxy finds an already-active context and skips re-refresh.
        context.addEventListener(new ServletContextListener() {
            @Override
            public void contextInitialized(ServletContextEvent sce) {
                appContext.setServletContext(sce.getServletContext());
                appContext.refresh();
            }

            @Override
            public void contextDestroyed(ServletContextEvent sce) {
                appContext.close();
            }
        });

        var dispatcher = new DispatcherServlet(appContext);
        var holder = new ServletHolder("spring-dispatcher", dispatcher);
        holder.setInitOrder(1);
        context.addServlet(holder, "/*");

        // ForwardedHeaderFilter — must be registered first so that X-Forwarded-Proto/Host/Port
        // headers from TLS-terminating ingress (OCP Route, nginx, etc.) are resolved before any
        // downstream filter builds absolute URLs (e.g. OAuth2 redirect URIs in Spring Security).
        var forwardedHeaderFilter = new FilterHolder(new ForwardedHeaderFilter());
        forwardedHeaderFilter.setAsyncSupported(true);
        for (String path : new String[] {"/api/*", "/login", "/logout", "/", "/oauth2/*", "/login/oauth2/*"}) {
            context.addFilter(forwardedHeaderFilter, path, EnumSet.of(DispatcherType.REQUEST, DispatcherType.ERROR));
        }

        // Spring Session filter — must be registered before Spring Security so that the distributed
        // session store is in place before Security reads authentication state from the session.
        // The filter is always wired (even for session-store=none, where it uses an in-memory store
        // equivalent to the default Jetty session behaviour).
        var sessionFilter = new FilterHolder(new DelegatingFilterProxy("springSessionRepositoryFilter", appContext));
        sessionFilter.setName("springSessionRepositoryFilter");
        sessionFilter.setAsyncSupported(true);
        for (String path : new String[] {"/api/*", "/login", "/logout", "/", "/oauth2/*", "/login/oauth2/*"}) {
            context.addFilter(sessionFilter, path, EnumSet.of(DispatcherType.REQUEST, DispatcherType.ERROR));
        }

        // Wire Spring Security filter chain into Jetty. Register only on the paths Spring Security
        // actually protects — never on /push/* or /proxy/* to avoid interfering with async git streaming.
        // /oauth2/* and /login/oauth2/* are needed for the OIDC authorization code flow; they are
        // no-ops when auth.provider is not "oidc" (the securityMatcher in SecurityConfig excludes them).
        var securityFilter = new FilterHolder(
                new DelegatingFilterProxy(AbstractSecurityWebApplicationInitializer.DEFAULT_FILTER_NAME, appContext));
        securityFilter.setName(AbstractSecurityWebApplicationInitializer.DEFAULT_FILTER_NAME);
        securityFilter.setAsyncSupported(true);
        for (String path : new String[] {"/api/*", "/login", "/logout", "/", "/oauth2/*", "/login/oauth2/*"}) {
            context.addFilter(securityFilter, path, EnumSet.of(DispatcherType.REQUEST, DispatcherType.ERROR));
        }

        log.info("Registered Spring MVC DispatcherServlet and Spring Security filter chain");
    }
}
