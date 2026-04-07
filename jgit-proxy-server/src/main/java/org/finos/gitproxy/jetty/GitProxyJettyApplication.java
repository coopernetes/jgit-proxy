package org.finos.gitproxy.jetty;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.finos.gitproxy.jetty.config.GitProxyConfig;
import org.finos.gitproxy.jetty.config.GitProxyConfigLoader;
import org.finos.gitproxy.jetty.config.JettyConfigurationBuilder;
import org.finos.gitproxy.jetty.reload.LiveConfigLoader;
import org.finos.gitproxy.provider.GitProxyProvider;

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
 * workflow UI, use {@code GitProxyWithDashboardApplication} from the {@code jgit-proxy-dashboard} module.
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

        var threadPool = new QueuedThreadPool();
        threadPool.setName("jgit-proxy-server");

        var server = new Server(threadPool);
        var connector = new ServerConnector(server);
        connector.setPort(configBuilder.getServerPort());
        server.addConnector(connector);

        GitProxyContext ctx = configBuilder.buildProxyContext();
        log.info("Push store initialized: {}", ctx.pushStore().getClass().getSimpleName());

        List<GitProxyProvider> providers = configBuilder.buildProviders();
        var context = new ServletContextHandler("/", false, false);

        GitProxyServletRegistrar.registerProviders(context, ctx, configBuilder, providers);

        server.setHandler(context);
        server.start();

        var liveConfigLoader = new LiveConfigLoader(
                configBuilder.buildConfigHolder(), gitProxyConfig, configBuilder.getReloadConfig());
        liveConfigLoader.start();
        Runtime.getRuntime().addShutdownHook(new Thread(liveConfigLoader::stop, "config-reload-shutdown"));

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

    /** Write PID file so {@code ./gradlew :jgit-proxy-server:stop} can find and kill this process. */
    public static void writePidFile() {
        String pidFilePath = System.getProperty("jgitproxy.pidfile");
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
