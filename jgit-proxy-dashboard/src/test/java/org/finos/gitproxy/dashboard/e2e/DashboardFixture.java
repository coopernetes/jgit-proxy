package org.finos.gitproxy.dashboard.e2e;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import java.util.EnumSet;
import java.util.List;
import org.eclipse.jetty.ee11.servlet.FilterHolder;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.finos.gitproxy.config.InMemoryProviderConfigurationSource;
import org.finos.gitproxy.dashboard.SecurityConfig;
import org.finos.gitproxy.dashboard.SpringWebConfig;
import org.finos.gitproxy.db.PushStoreFactory;
import org.finos.gitproxy.jetty.config.GitProxyConfig;
import org.finos.gitproxy.user.StaticUserStore;
import org.finos.gitproxy.user.UserEntry;
import org.finos.gitproxy.user.UserStore;
import org.springframework.security.web.context.AbstractSecurityWebApplicationInitializer;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Starts a Jetty server with the full Spring MVC + Spring Security stack wired from a supplied {@link GitProxyConfig}.
 * No git proxy servlets are registered — this fixture is focused purely on testing authentication flows.
 *
 * <p>Listens on an ephemeral port chosen by the OS. Call {@link #getBaseUrl()} after construction to get the URL.
 *
 * <p>Typical usage:
 *
 * <pre>{@code
 * GitProxyConfig config = new GitProxyConfig();
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
     * via {@link #DashboardFixture(GitProxyConfig, UserStore)} if the test needs them.
     */
    DashboardFixture(GitProxyConfig config) throws Exception {
        this(config, new StaticUserStore(List.of()));
    }

    /**
     * Creates and starts a dashboard server with the given config and a pre-populated user store. Use this when testing
     * the static auth provider or when the API response needs user profile data.
     */
    DashboardFixture(GitProxyConfig config, UserStore userStore) throws Exception {
        var appContext = new AnnotationConfigWebApplicationContext();
        appContext.register(SpringWebConfig.class, SecurityConfig.class);
        appContext.addBeanFactoryPostProcessor(bf -> {
            bf.registerSingleton("userStore", userStore);
            bf.registerSingleton("gitProxyConfig", config);
            bf.registerSingleton("pushStore", PushStoreFactory.inMemory());
            bf.registerSingleton("providers", new InMemoryProviderConfigurationSource(List.of()));
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
        // restrict the paths the way GitProxyWithDashboardApplication does.
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
        var config = new GitProxyConfig();
        config.getAuth().setProvider("local");
        return new DashboardFixture(config, new StaticUserStore(users));
    }
}
