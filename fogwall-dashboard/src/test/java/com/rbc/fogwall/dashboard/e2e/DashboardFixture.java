package com.rbc.fogwall.dashboard.e2e;

import com.rbc.fogwall.config.FogwallConfig;
import com.rbc.fogwall.config.JettyConfigurationBuilder;
import com.rbc.fogwall.crypto.TokenCipherProvider;
import com.rbc.fogwall.dashboard.SecurityConfig;
import com.rbc.fogwall.dashboard.SpringWebConfig;
import com.rbc.fogwall.db.PushStoreFactory;
import com.rbc.fogwall.db.ScmApiActionStoreFactory;
import com.rbc.fogwall.db.jdbc.DataSourceFactory;
import com.rbc.fogwall.db.memory.InMemoryFetchStore;
import com.rbc.fogwall.db.memory.InMemoryUrlRuleRegistry;
import com.rbc.fogwall.git.LocalRepositoryCache;
import com.rbc.fogwall.jetty.reload.LiveConfigLoader;
import com.rbc.fogwall.permission.InMemoryRepoPermissionStore;
import com.rbc.fogwall.permission.RepoPermissionService;
import com.rbc.fogwall.provider.InMemoryProviderRegistry;
import com.rbc.fogwall.user.ReadOnlyUserStore;
import com.rbc.fogwall.user.StaticUserStore;
import com.rbc.fogwall.user.UserEntry;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.eclipse.jetty.ee11.servlet.FilterHolder;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.springframework.security.web.context.AbstractSecurityWebApplicationInitializer;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Starts a Jetty server with the full Spring MVC + Spring Security stack wired from a supplied {@link FogwallConfig}.
 * No git proxy servlets are registered — this fixture is focused purely on testing authentication flows.
 *
 * <p>Listens on an ephemeral port chosen by the OS. Call {@link #getBaseUrl()} after construction to get the URL.
 *
 * <p>Typical usage:
 *
 * <pre>{@code
 * fogwallConfig config = new fogwallConfig();
 * config.getAuth().setProvider("ldap");
 * config.getAuth().getLdap().setUrl("ldap://localhost:1389/dc=example,dc=com");
 *
 * try (var dashboard = new DashboardFixture(config)) {
 *     // ... make HTTP requests to dashboard.getBaseUrl() ...
 * }
 * }</pre>
 */
class DashboardFixture implements AutoCloseable {

    private final Server server;
    private final int port;

    /**
     * Creates and starts a dashboard server with the given config. An empty {@link StaticUserStore} is used; seed users
     * via {@link #DashboardFixture(FogwallConfig, ReadOnlyUserStore)} if the test needs them.
     */
    DashboardFixture(FogwallConfig config) throws Exception {
        this(config, new StaticUserStore(List.of()));
    }

    /**
     * Creates and starts a dashboard server with the given config and a pre-populated user store. Use this when testing
     * the static auth provider or when the API response needs user profile data.
     */
    DashboardFixture(FogwallConfig config, ReadOnlyUserStore userStore) throws Exception {
        var appContext = new AnnotationConfigWebApplicationContext();
        appContext.register(SpringWebConfig.class, SecurityConfig.class);
        var configBuilder = new JettyConfigurationBuilder(config);
        var configHolder = configBuilder.buildConfigHolder();
        var liveConfigLoader = new LiveConfigLoader(configHolder, config, config.getReload(), null, null);

        // AdminCacheController constructor-injects both mirror caches by qualifier; the production context registers
        // them from FogwallContext, so this fixture must supply the same two singletons. Empty caches are fine — the
        // dashboard e2e/startup tests never push through, they only need the beans to exist so the context wires up.
        var serverCache = new LocalRepositoryCache(
                Files.createTempDirectory("fogwall-test-server-cache-" + UUID.randomUUID()), false);
        var proxyCache = new LocalRepositoryCache(
                Files.createTempDirectory("fogwall-test-proxy-cache-" + UUID.randomUUID()), false);

        appContext.addBeanFactoryPostProcessor(bf -> {
            bf.registerSingleton("serverCache", serverCache);
            bf.registerSingleton("proxyCache", proxyCache);
            bf.registerSingleton("userStore", userStore);
            bf.registerSingleton("fogwallConfig", config);
            bf.registerSingleton("pushStore", PushStoreFactory.h2InMemory("test-" + UUID.randomUUID()));
            bf.registerSingleton("providers", new InMemoryProviderRegistry(List.of()));
            bf.registerSingleton("configHolder", configHolder);
            bf.registerSingleton("liveConfigLoader", liveConfigLoader);
            bf.registerSingleton("repoRegistry", new InMemoryUrlRuleRegistry());
            bf.registerSingleton("fetchStore", new InMemoryFetchStore());
            bf.registerSingleton(
                    "scmApiActionStore",
                    ScmApiActionStoreFactory.fromDataSource(
                            DataSourceFactory.h2InMemory("test-scm-api-" + UUID.randomUUID())));
            bf.registerSingleton("repoPermissionService", new RepoPermissionService(new InMemoryRepoPermissionStore()));
            bf.registerSingleton(
                    "tokenCipherProvider",
                    TokenCipherProvider.initialize(
                            null,
                            Path.of(
                                    System.getProperty("java.io.tmpdir"),
                                    "fogwall-test-scm-oauth-key-" + UUID.randomUUID())));
        });

        server = new Server();
        var connector = new ServerConnector(server);
        connector.setPort(0); // ephemeral
        server.addConnector(connector);

        var context = new ServletContextHandler("/", true, false);

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
        context.addServlet(new ServletHolder("spring", dispatcher), "/*");

        // Apply Spring Security to all paths. There are no git servlets here, so we don't need to
        // restrict the paths the way fogwallWithDashboardApplication does.
        var securityFilter = new FilterHolder(
                new DelegatingFilterProxy(AbstractSecurityWebApplicationInitializer.DEFAULT_FILTER_NAME, appContext));
        securityFilter.setName(AbstractSecurityWebApplicationInitializer.DEFAULT_FILTER_NAME);
        securityFilter.setAsyncSupported(true);
        context.addFilter(securityFilter, "/*", EnumSet.of(DispatcherType.REQUEST, DispatcherType.ERROR));

        server.setHandler(context);
        server.start();

        port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    /** Base URL of the dashboard, e.g. {@code http://localhost:54321}. */
    String getBaseUrl() {
        return "http://localhost:" + port;
    }

    /** Port the server is listening on. */
    int getPort() {
        return port;
    }

    @Override
    public void close() throws Exception {
        server.stop();
    }

    /** Convenience factory for a config with local auth and the given pre-hashed users. */
    static DashboardFixture withLocalUsers(List<UserEntry> users) throws Exception {
        var config = new FogwallConfig();
        config.getAuth().setProvider("local");
        return new DashboardFixture(config, new StaticUserStore(users));
    }
}
