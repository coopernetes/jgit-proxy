package org.finos.gitproxy.api;

import java.nio.file.Files;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.finos.gitproxy.config.InMemoryProviderConfigurationSource;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.git.LocalRepositoryCache;
import org.finos.gitproxy.jetty.GitProxyServletRegistrar;
import org.finos.gitproxy.jetty.config.JettyConfigurationBuilder;
import org.finos.gitproxy.jetty.config.JettyConfigurationLoader;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Jetty application that runs the git proxy server together with the Spring MVC dashboard and REST API. This is the
 * entry point to use when you want the approval workflow UI alongside the proxy.
 *
 * <p>For a proxy-only deployment (no UI, no REST API), use {@code GitProxyJettyApplication} in
 * {@code jgit-proxy-server} instead.
 *
 * <p>Git servlets are registered at {@code /push/*} and {@code /proxy/*}. Spring's DispatcherServlet is registered at
 * {@code /*} — the more-specific git paths take precedence per the servlet spec, so Spring only handles {@code /api/*},
 * {@code /}, and static assets.
 */
@Slf4j
public class GitProxyWithDashboardApplication {

    public static void main(String[] args) throws Exception {
        log.info("Starting JGit Proxy with Dashboard...");
        writePidFile();

        var configLoader = new JettyConfigurationLoader();
        var configBuilder = new JettyConfigurationBuilder(configLoader);

        var threadPool = new QueuedThreadPool();
        threadPool.setName("jgit-proxy-api");

        var server = new Server(threadPool);
        var connector = new ServerConnector(server);
        connector.setPort(configBuilder.getServerPort());
        server.addConnector(connector);

        PushStore pushStore = configBuilder.buildPushStore();
        log.info("Push store initialized: {}", pushStore.getClass().getSimpleName());

        var storeForwardCache = new LocalRepositoryCache(Files.createTempDirectory("jgit-proxy-sf-"), 0, true);
        var proxyCache = new LocalRepositoryCache();

        List<GitProxyProvider> providers = configBuilder.buildProviders();
        var providerConfig = new InMemoryProviderConfigurationSource(providers);

        var context = new ServletContextHandler("/", false, false);
        var commitConfig = GitProxyServletRegistrar.buildCommitConfig();

        // Register git proxy servlets (store-and-forward + transparent proxy) for each provider
        String serviceUrl = configBuilder.getServiceUrl();
        for (GitProxyProvider provider : providerConfig.getProviders()) {
            log.info("Registering provider: {}", provider.getName());
            GitProxyServletRegistrar.registerGitServlet(
                    context, provider, storeForwardCache, commitConfig, pushStore, serviceUrl);
            GitProxyServletRegistrar.registerProxyServlet(context, provider);
            GitProxyServletRegistrar.registerFilters(
                    context, provider, proxyCache, configBuilder, commitConfig, pushStore, serviceUrl);
        }

        // Spring MVC DispatcherServlet at /* — git-specific paths take precedence per servlet spec
        registerSpringServlet(context, pushStore);

        server.setHandler(context);
        server.start();

        log.info("JGit Proxy with Dashboard started on port {}", connector.getPort());
        log.info("  Dashboard: http://localhost:{}/", connector.getPort());
        log.info("  API:       http://localhost:{}/api/push", connector.getPort());
        log.info("  Health:    http://localhost:{}/api/health", connector.getPort());

        server.join();
    }

    private static void registerSpringServlet(ServletContextHandler context, PushStore pushStore) {
        var appContext = new AnnotationConfigWebApplicationContext();
        appContext.register(SpringWebConfig.class);
        appContext.addBeanFactoryPostProcessor(bf -> bf.registerSingleton("pushStore", pushStore));

        var dispatcher = new DispatcherServlet(appContext);
        var holder = new ServletHolder("spring-dispatcher", dispatcher);
        holder.setInitOrder(1);
        context.addServlet(holder, "/*");
        log.info("Registered Spring MVC DispatcherServlet at /*");
    }

    private static void writePidFile() {
        String pidFilePath = System.getProperty("jgitproxy.pidfile");
        if (pidFilePath == null) return;
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
