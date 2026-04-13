package org.finos.gitproxy.e2e;

import jakarta.servlet.DispatcherType;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import org.eclipse.jetty.ee11.servlet.FilterHolder;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jgit.http.server.GitServlet;
import org.finos.gitproxy.approval.AutoApprovalGateway;
import org.finos.gitproxy.config.GpgConfig;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.PushStoreFactory;
import org.finos.gitproxy.db.memory.InMemoryRepoRegistry;
import org.finos.gitproxy.git.*;
import org.finos.gitproxy.jetty.config.GitProxyConfigLoader;
import org.finos.gitproxy.jetty.config.JettyConfigurationBuilder;
import org.finos.gitproxy.jetty.reload.ConfigHolder;
import org.finos.gitproxy.jetty.reload.LiveConfigLoader.Section;
import org.finos.gitproxy.provider.GenericProxyProvider;
import org.finos.gitproxy.servlet.GitProxyServlet;
import org.finos.gitproxy.servlet.filter.*;

/**
 * Variant of {@link JettyProxyFixture} that wires all hot-reloadable config through a live {@link ConfigHolder} and an
 * {@link InMemoryRepoRegistry}, enabling tests to verify that config changes take effect mid-test without restarting
 * the server.
 *
 * <p>Both the transparent proxy path ({@code /proxy/...}) and the store-and-forward path ({@code /push/...}) use
 * supplier references into the {@link ConfigHolder}. Updating the holder — via {@link #reloadSection(Path, Section)} —
 * immediately affects the next request on either path.
 *
 * <p>Uses {@link AutoApprovalGateway} so that allowed pushes go through without requiring a human review step.
 */
class HotReloadJettyFixture implements AutoCloseable {

    private static final String PUSH_PREFIX = "/push";
    private static final String PROXY_PREFIX = "/proxy";

    private final Server server;
    private final int port;
    private final PushStore pushStore;
    private final String providerId;
    private final ConfigHolder configHolder;
    private final InMemoryRepoRegistry configRegistry;

    HotReloadJettyFixture(URI giteaUri, ConfigHolder configHolder, InMemoryRepoRegistry configRegistry)
            throws Exception {
        this.configHolder = configHolder;
        this.configRegistry = configRegistry;

        server = new Server();
        var connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        pushStore = PushStoreFactory.inMemory();
        var storeForwardCache =
                new LocalRepositoryCache(Files.createTempDirectory("git-proxy-java-hotreload-sf-"), 0, true);
        var proxyCache = new LocalRepositoryCache();

        var provider = GenericProxyProvider.builder()
                .name("gitea-e2e-hotreload")
                .uri(giteaUri)
                .basePath("")
                .build();
        this.providerId = provider.getProviderId();

        var context = new ServletContextHandler("/", false, false);
        var approvalGateway = new AutoApprovalGateway(pushStore);

        // Store-and-forward GitServlet on /push/...
        var resolver = new StoreAndForwardRepositoryResolver(storeForwardCache, provider);
        var gitServlet = new GitServlet();
        gitServlet.setRepositoryResolver(resolver);
        gitServlet.setReceivePackFactory(new StoreAndForwardReceivePackFactory(
                provider,
                configHolder::getCommitConfig,
                configHolder::getDiffScanConfig,
                configHolder::getSecretScanConfig,
                GpgConfig.defaultConfig(),
                null,
                null,
                pushStore,
                approvalGateway,
                null,
                Duration.ofSeconds(30),
                List.of(),
                configRegistry));
        gitServlet.setUploadPackFactory(new StoreAndForwardUploadPackFactory());

        String pushServletPath = PUSH_PREFIX + provider.servletPath();
        String pushMapping = pushServletPath + "/*";
        var gitHolder = new ServletHolder(gitServlet);
        gitHolder.setName("git-gitea-hotreload");
        context.addServlet(gitHolder, pushMapping);
        context.addFilter(
                new FilterHolder(new SmartHttpErrorFilter()), pushMapping, EnumSet.of(DispatcherType.REQUEST));
        context.addFilter(
                new FilterHolder(new BasicAuthChallengeFilter()), pushMapping, EnumSet.of(DispatcherType.REQUEST));

        // Transparent proxy GitProxyServlet on /proxy/...
        String proxyServletPath = PROXY_PREFIX + provider.servletPath();
        String proxyMapping = proxyServletPath + "/*";

        var proxyServlet = new GitProxyServlet(pushStore);
        var proxyHolder = new ServletHolder(proxyServlet);
        proxyHolder.setName("proxy-gitea-hotreload");
        proxyHolder.setInitParameter("proxyTo", giteaUri.toString());
        proxyHolder.setInitParameter("prefix", proxyServletPath);
        proxyHolder.setInitParameter("hostHeader", giteaUri.getHost());
        proxyHolder.setInitParameter("preserveHost", "false");
        context.addServlet(proxyHolder, proxyMapping);

        String serviceUrl = "http://localhost";
        addFilter(context, proxyMapping, new PushStoreAuditFilter(pushStore));
        addFilter(context, proxyMapping, new ForceGitClientFilter());
        addFilter(context, proxyMapping, new ParseGitRequestFilter(provider, PROXY_PREFIX));
        addFilter(context, proxyMapping, new EnrichPushCommitsFilter(provider, proxyCache, PROXY_PREFIX));
        addFilter(context, proxyMapping, new AllowApprovedPushFilter(pushStore, serviceUrl));
        // Always register the URL rule filter, backed by the live configRegistry.
        // Note: proxy mode is fail-closed — an empty registry (OpenMode) results in 403.
        // Tests must seed the registry with at least one allow rule before making requests.
        addFilter(
                context,
                proxyMapping,
                new UrlRuleAggregateFilter(100, provider, List.of(), PROXY_PREFIX, null, configRegistry));
        addFilter(context, proxyMapping, new CheckEmptyBranchFilter());
        addFilter(context, proxyMapping, new CheckHiddenCommitsFilter(provider));
        addFilter(context, proxyMapping, new CheckAuthorEmailsFilter(configHolder::getCommitConfig));
        addFilter(context, proxyMapping, new CheckCommitMessagesFilter(configHolder::getCommitConfig));
        addFilter(context, proxyMapping, new ScanDiffFilter(provider, configHolder::getDiffScanConfig));
        addFilter(context, proxyMapping, new SecretScanningFilter(configHolder::getSecretScanConfig));
        addFilter(context, proxyMapping, new GpgSignatureFilter(GpgConfig.defaultConfig()));
        addFilter(context, proxyMapping, new ValidationSummaryFilter());
        addFilter(context, proxyMapping, new FetchFinalizerFilter());
        addFilter(context, proxyMapping, new PushFinalizerFilter(serviceUrl, approvalGateway));
        addFilter(context, proxyMapping, new AuditLogFilter());

        server.setHandler(context);
        server.start();

        port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    /** The port the proxy is listening on. */
    int getPort() {
        return port;
    }

    PushStore getPushStore() {
        return pushStore;
    }

    String getProviderId() {
        return providerId;
    }

    String getProxyBase() {
        return "http://localhost:" + port + PROXY_PREFIX + "/localhost";
    }

    /**
     * Reloads the specified config section by loading {@code overrideYaml} on top of the base classpath config and
     * applying the result to the live {@link ConfigHolder} and/or {@link InMemoryRepoRegistry}.
     *
     * <p>This exercises the same code path as {@link org.finos.gitproxy.jetty.reload.LiveConfigLoader} — the YAML is
     * parsed by {@link GitProxyConfigLoader#loadWithOverride}, then the relevant section is built by
     * {@link JettyConfigurationBuilder} and applied atomically.
     */
    void reloadSection(Path overrideYaml, Section section) throws Exception {
        var newConfig = GitProxyConfigLoader.loadWithOverride(overrideYaml);
        var builder = new JettyConfigurationBuilder(newConfig);
        switch (section) {
            case COMMIT -> configHolder.update(builder.buildCommitConfig());
            case DIFF_SCAN -> configHolder.update(builder.buildDiffScanConfig());
            case SECRET_SCAN -> configHolder.update(builder.buildSecretScanConfig());
            case RULES -> configRegistry.seedFromConfig(builder.buildConfigRules(newConfig));
            case ALL -> {
                configHolder.update(builder.buildCommitConfig());
                configHolder.update(builder.buildDiffScanConfig());
                configHolder.update(builder.buildSecretScanConfig());
                configRegistry.seedFromConfig(builder.buildConfigRules(newConfig));
            }
            default -> throw new IllegalArgumentException("Unsupported section: " + section);
        }
    }

    @Override
    public void close() throws Exception {
        server.stop();
    }

    private static void addFilter(ServletContextHandler ctx, String mapping, jakarta.servlet.Filter filter) {
        var holder = new FilterHolder(filter);
        holder.setAsyncSupported(true);
        ctx.addFilter(holder, mapping, EnumSet.of(DispatcherType.REQUEST));
    }
}
