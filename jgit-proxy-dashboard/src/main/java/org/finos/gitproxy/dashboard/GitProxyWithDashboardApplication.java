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
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.finos.gitproxy.approval.UiApprovalGateway;
import org.finos.gitproxy.config.InMemoryProviderConfigurationSource;
import org.finos.gitproxy.config.ProviderConfigurationSource;
import org.finos.gitproxy.db.RepoRegistry;
import org.finos.gitproxy.jetty.GitProxyContext;
import org.finos.gitproxy.jetty.GitProxyJettyApplication;
import org.finos.gitproxy.jetty.GitProxyServletRegistrar;
import org.finos.gitproxy.jetty.config.GitProxyConfigLoader;
import org.finos.gitproxy.jetty.config.JettyConfigurationBuilder;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.springframework.security.web.context.AbstractSecurityWebApplicationInitializer;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Jetty application that runs the git proxy server together with the Spring MVC dashboard and REST API. This is the
 * entry point to use when you want the approval workflow UI alongside the proxy.
 *
 * <p>For a proxy-only deployment (no UI, no REST API), use {@code GitProxyJettyApplication} in
 * {@code jgit-proxy-server} instead.
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

        var gitProxyConfig = GitProxyConfigLoader.load();
        var configBuilder = new JettyConfigurationBuilder(gitProxyConfig);

        var threadPool = new QueuedThreadPool();
        threadPool.setName("jgit-proxy-dashboard");

        var server = new Server(threadPool);
        var connector = new ServerConnector(server);
        connector.setPort(configBuilder.getServerPort());
        server.addConnector(connector);

        // buildPushStore() is called first so we can hand the same instance to UiApprovalGateway.
        // buildProxyContext() will reuse the cached instance internally.
        var pushStore = configBuilder.buildPushStore();
        log.info("Push store initialized: {}", pushStore.getClass().getSimpleName());

        RepoRegistry repoRegistry = configBuilder.buildRepoRegistry();

        // Always use UiApprovalGateway when running with the dashboard — the REST API is what drives approval.
        // This is intentionally not derived from approval-mode config: the dashboard deployment always needs
        // UI-based review regardless of what is set in the config file.
        GitProxyContext ctx = configBuilder.buildProxyContext(new UiApprovalGateway(pushStore));

        List<GitProxyProvider> providers = configBuilder.buildProviders();
        var providerConfig = new InMemoryProviderConfigurationSource(providers);

        var context = new ServletContextHandler("/", true, false);

        // Register git proxy servlets (store-and-forward + transparent proxy) for each provider
        GitProxyServletRegistrar.registerProviders(context, ctx, configBuilder, providers);

        // Spring MVC DispatcherServlet at /* - git-specific paths take precedence per servlet spec
        registerSpringServlet(context, ctx, providerConfig, gitProxyConfig, repoRegistry);

        server.setHandler(context);
        server.start();

        log.info("JGit Proxy with Dashboard started on port {}", connector.getPort());
        log.info("  Dashboard: http://localhost:{}/", connector.getPort());
        log.info("  API:       http://localhost:{}/api/push", connector.getPort());
        log.info("  Health:    http://localhost:{}/api/health", connector.getPort());

        server.join();
    }

    private static void registerSpringServlet(
            ServletContextHandler context,
            GitProxyContext ctx,
            ProviderConfigurationSource providers,
            org.finos.gitproxy.jetty.config.GitProxyConfig gitProxyConfig,
            RepoRegistry repoRegistry) {
        var appContext = new AnnotationConfigWebApplicationContext();
        appContext.register(SpringWebConfig.class, SecurityConfig.class);
        appContext.addBeanFactoryPostProcessor(bf -> {
            bf.registerSingleton("pushStore", ctx.pushStore());
            bf.registerSingleton("providers", providers);
            bf.registerSingleton("userStore", ctx.userStore());
            bf.registerSingleton("gitProxyConfig", gitProxyConfig);
            bf.registerSingleton("repoRegistry", repoRegistry);
            bf.registerSingleton("fetchStore", ctx.fetchStore());
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
