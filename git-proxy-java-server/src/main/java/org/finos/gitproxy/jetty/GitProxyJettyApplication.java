package org.finos.gitproxy.jetty;

import java.nio.file.Path;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.finos.gitproxy.jetty.config.GitProxyConfig;
import org.finos.gitproxy.jetty.config.GitProxyConfigLoader;
import org.finos.gitproxy.jetty.config.JettyConfigurationBuilder;
import org.finos.gitproxy.jetty.config.TlsConfig;
import org.finos.gitproxy.jetty.reload.LiveConfigLoader;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.tls.SslUtil;

/**
 * Standalone Jetty server application for the JGit proxy. Registers two servlets per provider:
 *
 * <ul>
 *   <li><b>GitServlet</b> on {@code /push/...} - store-and-forward mode using JGit's native ReceivePack/UploadPack
 *       stack with sideband validation feedback
 *   <li><b>GitProxyServlet</b> on {@code /proxy/...} - transparent HTTP proxy bypass
 * </ul>
 *
 * <p>This entry point runs the proxy only - no dashboard, no REST API. For the full stack including the approval
 * workflow UI, use {@code GitProxyWithDashboardApplication} from the {@code git-proxy-java-dashboard} module.
 *
 * <p>Configuration is loaded from {@code git-proxy.yml} and {@code git-proxy-local.yml}, overridable with
 * {@code GITPROXY_} environment variables.
 */
@Slf4j
public class GitProxyJettyApplication {

    public static void main(String[] args) throws Exception {
        log.info("Starting JGit Proxy (proxy only - no dashboard)...");
        writePidFile();

        GitProxyConfig gitProxyConfig = GitProxyConfigLoader.load();
        var configBuilder = new JettyConfigurationBuilder(gitProxyConfig);
        configBuilder.validateProviderReferences(); // fail fast before any DB or port setup

        var threadPool = new QueuedThreadPool();
        threadPool.setName("git-proxy-java-server");

        var server = new Server(threadPool);
        var connector = new ServerConnector(server);
        connector.setPort(configBuilder.getServerPort());
        server.addConnector(connector);

        // Graceful shutdown: drain in-flight requests for up to 30s on SIGTERM before the JVM exits.
        // Without this, rolling deploys on Kubernetes/OCP hard-kill active git push/proxy streams.
        server.setStopTimeout(30_000);
        server.setStopAtShutdown(true);

        TlsConfig tls = configBuilder.getTlsConfig();
        if (tls.isServerTlsConfigured()) {
            server.addConnector(buildHttpsConnector(server, tls));
            log.info("HTTPS listener configured on port {}", tls.getPort());
        }

        GitProxyContext ctx = configBuilder.buildProxyContext();
        log.info("Push store initialized: {}", ctx.pushStore().getClass().getSimpleName());

        List<GitProxyProvider> providers = configBuilder.buildProviders();
        var context = new ServletContextHandler("/", false, false);

        GitProxyServletRegistrar.registerProviders(context, ctx, configBuilder, providers);

        var liveConfigLoader = new LiveConfigLoader(
                configBuilder.buildConfigHolder(),
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

        server.setHandler(context);
        server.start();

        log.info("JGit Proxy started on port {}", connector.getPort());
        for (GitProxyProvider provider : providers) {
            log.info(
                    "  - {} (store-and-forward) at {}{}",
                    provider.getName(),
                    GitProxyServletRegistrar.PUSH_PATH_PREFIX,
                    provider.servletMapping());
            log.info(
                    "  - {} (proxy bypass) at {}{}",
                    provider.getName(),
                    GitProxyServletRegistrar.PROXY_PATH_PREFIX,
                    provider.servletMapping());
        }

        server.join();
    }

    public static ServerConnector buildHttpsConnector(Server server, TlsConfig tls) throws Exception {
        var sslContextFactory = new SslContextFactory.Server();
        if (tls.getCertificate() != null && tls.getKey() != null) {
            sslContextFactory.setSslContext(
                    SslUtil.buildServerSslContext(Path.of(tls.getCertificate()), Path.of(tls.getKey())));
        } else {
            TlsConfig.KeystoreConfig ks = tls.getKeystore();
            sslContextFactory.setKeyStorePath(ks.getPath());
            sslContextFactory.setKeyStorePassword(ks.getPassword());
            sslContextFactory.setKeyStoreType(ks.getType());
        }
        var http = new HttpConnectionFactory();
        var ssl = new SslConnectionFactory(sslContextFactory, http.getProtocol());
        var connector = new ServerConnector(server, ssl, http);
        connector.setPort(tls.getPort());
        return connector;
    }

    /** Write PID file so {@code ./gradlew :git-proxy-java-server:stop} can find and kill this process. */
    public static void writePidFile() {
        String pidFilePath = System.getProperty("gitproxyjava.pidfile");
        if (pidFilePath == null) return;
        try {
            var pidFile = java.nio.file.Path.of(pidFilePath);
            java.nio.file.Files.createDirectories(pidFile.getParent());
            java.nio.file.Files.writeString(
                    pidFile, String.valueOf(ProcessHandle.current().pid()));
            log.info("Wrote PID file: {}", pidFilePath);
        } catch (Exception e) {
            log.warn("Could not write PID file: {}", e.getMessage());
        }
    }
}
