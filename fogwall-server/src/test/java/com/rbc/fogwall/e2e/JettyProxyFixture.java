package com.rbc.fogwall.e2e;

import com.rbc.fogwall.approval.ApprovalGateway;
import com.rbc.fogwall.approval.AutoApprovalGateway;
import com.rbc.fogwall.approval.UiApprovalGateway;
import com.rbc.fogwall.config.BinaryBlobConfig;
import com.rbc.fogwall.config.BlockConfig;
import com.rbc.fogwall.config.CommitConfig;
import com.rbc.fogwall.config.ContentPatternConfig;
import com.rbc.fogwall.config.DiffScanConfig;
import com.rbc.fogwall.config.EmailRule;
import com.rbc.fogwall.config.GpgConfig;
import com.rbc.fogwall.config.SecretScanConfig;
import com.rbc.fogwall.db.PushStore;
import com.rbc.fogwall.db.PushStoreFactory;
import com.rbc.fogwall.db.memory.InMemoryUrlRuleRegistry;
import com.rbc.fogwall.db.model.AccessRule;
import com.rbc.fogwall.db.model.MatchTarget;
import com.rbc.fogwall.db.model.MatchType;
import com.rbc.fogwall.git.DisabledFetchUploadPackFactory;
import com.rbc.fogwall.git.LocalRepositoryCache;
import com.rbc.fogwall.git.ServerReceivePackFactory;
import com.rbc.fogwall.git.ServerRepositoryResolver;
import com.rbc.fogwall.git.ServerUploadPackFactory;
import com.rbc.fogwall.git.UpstreamAuthProbe;
import com.rbc.fogwall.jetty.BlockingContentHandler;
import com.rbc.fogwall.jetty.FogwallJettyApplication;
import com.rbc.fogwall.permission.RepoPermissionService;
import com.rbc.fogwall.provider.GenericProxyProvider;
import com.rbc.fogwall.service.PushIdentityResolver;
import com.rbc.fogwall.servlet.FogwallServlet;
import com.rbc.fogwall.servlet.filter.*;
import jakarta.servlet.DispatcherType;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.eclipse.jetty.ee11.servlet.FilterHolder;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jgit.http.server.GitServlet;

/**
 * Starts and stops a real Jetty server wired up identically to {@code fogwallJettyApplication} but with a single
 * {@link GenericProxyProvider} pointing at the test Gitea instance, listening on an ephemeral port.
 *
 * <p>Intended for use inside {@code @Tag("e2e")} tests as a JUnit {@code @BeforeAll} / {@code @AfterAll} resource.
 */
class JettyProxyFixture implements AutoCloseable {

    private static final String PUSH_PREFIX = "/push";
    private static final String PROXY_PREFIX = "/proxy";

    private final Server server;
    private final int port;
    private final PushStore pushStore;
    private final String providerId;
    private final String giteaHostPort;

    /**
     * Create a fixture with UI (block-then-approve) approval mode for the transparent proxy path. This is the
     * production default: clean pushes are blocked pending human review.
     */
    JettyProxyFixture(URI giteaUri) throws Exception {
        this(giteaUri, UiApprovalGateway::new);
    }

    /**
     * Create a fixture with a custom approval gateway factory for the transparent proxy path. The factory receives the
     * shared {@link PushStore} and returns the gateway to use in {@link PushFinalizerFilter}.
     */
    JettyProxyFixture(URI giteaUri, Function<PushStore, ApprovalGateway> proxyGatewayFactory) throws Exception {
        this(giteaUri, proxyGatewayFactory, null, null);
    }

    /**
     * Create a fixture with optional identity and permission filters enabled on the transparent proxy path. When
     * {@code identityResolver} is non-null, {@link CheckUserPushPermissionFilter} and
     * {@link CommitAttributionPolicyFilter} are added to the filter chain (matching production order). The
     * {@code permissionService} must also be non-null when identity checking is enabled. Defaults to committer=warn,
     * author=off.
     */
    JettyProxyFixture(
            URI giteaUri,
            Function<PushStore, ApprovalGateway> proxyGatewayFactory,
            PushIdentityResolver identityResolver,
            RepoPermissionService permissionService)
            throws Exception {
        this(
                giteaUri,
                proxyGatewayFactory,
                identityResolver,
                permissionService,
                CommitConfig.CommitAttributionPolicyConfig.builder().build());
    }

    /**
     * Create a fixture with URL allow/deny rules enforced on both the transparent proxy path and the server mode path.
     * Uses auto-approve for the approval gateway so that allowed pushes go through without a review step.
     */
    JettyProxyFixture(URI giteaUri, List<AccessRule> configRules) throws Exception {
        this(
                giteaUri,
                AutoApprovalGateway::new,
                null,
                null,
                CommitConfig.CommitAttributionPolicyConfig.builder().build(),
                configRules,
                true);
    }

    /**
     * Create a fixture with the server mode clone/fetch toggle set (#478). {@code serveFetch=false} makes server mode
     * push-only: {@code git-upload-pack} is not served and clone/fetch is refused, while push (receive-pack) still
     * works.
     */
    JettyProxyFixture(URI giteaUri, boolean serveFetch) throws Exception {
        this(
                giteaUri,
                UiApprovalGateway::new,
                null,
                null,
                CommitConfig.CommitAttributionPolicyConfig.builder().build(),
                List.of(),
                serveFetch);
    }

    /**
     * Create a fixture with identity and permission filters enabled, using an explicit identity verification config.
     */
    JettyProxyFixture(
            URI giteaUri,
            Function<PushStore, ApprovalGateway> proxyGatewayFactory,
            PushIdentityResolver identityResolver,
            RepoPermissionService permissionService,
            CommitConfig.CommitAttributionPolicyConfig attributionPolicyConfig)
            throws Exception {
        this(
                giteaUri,
                proxyGatewayFactory,
                identityResolver,
                permissionService,
                attributionPolicyConfig,
                List.of(),
                true);
    }

    /** Full constructor — all options. */
    JettyProxyFixture(
            URI giteaUri,
            Function<PushStore, ApprovalGateway> proxyGatewayFactory,
            PushIdentityResolver identityResolver,
            RepoPermissionService permissionService,
            CommitConfig.CommitAttributionPolicyConfig attributionPolicyConfig,
            List<AccessRule> configRules,
            boolean serveFetch)
            throws Exception {
        // Same thread model as production: bounded virtual-thread dispatch on top of the platform pool.
        var threadPool = new QueuedThreadPool();
        threadPool.setName("e2e-fixture");
        server = new Server(threadPool);
        FogwallJettyApplication.enableVirtualThreads(server, threadPool, "e2e-fixture", 512);
        var connector = new ServerConnector(server);
        connector.setPort(0); // ephemeral
        server.addConnector(connector);

        pushStore = PushStoreFactory.h2InMemory("test-" + UUID.randomUUID());
        var serverCache = new LocalRepositoryCache(Files.createTempDirectory("fogwall-e2e-sf-"), 0, true);
        var proxyCache = new LocalRepositoryCache();

        var provider =
                GenericProxyProvider.builder().name("gitea-e2e").uri(giteaUri).build();
        provider.setServeFetch(serveFetch); // #478 fetch toggle
        this.providerId = provider.getProviderId();
        this.giteaHostPort = giteaUri.getHost() + ":" + giteaUri.getPort();

        var commitConfig = buildCommitConfig();
        var context = new ServletContextHandler("/", false, false);

        var urlRuleRegistry = new InMemoryUrlRuleRegistry();
        if (configRules.isEmpty()) {
            // No explicit rules — seed a catch-all allow rule so the proxy is open
            urlRuleRegistry.save(AccessRule.builder()
                    .ruleOrder(1)
                    .access(AccessRule.Access.ALLOW)
                    .operation(AccessRule.Operation.BOTH)
                    .target(MatchTarget.OWNER)
                    .value("*")
                    .matchType(MatchType.GLOB)
                    .build());
        } else {
            configRules.forEach(urlRuleRegistry::save);
        }

        // Server mode GitServlet on /push/...
        var resolver = new ServerRepositoryResolver(serverCache, provider);
        var gitServlet = new GitServlet();
        gitServlet.setRepositoryResolver(resolver);
        var approvalGateway = new AutoApproveGateway(pushStore);
        gitServlet.setReceivePackFactory(new ServerReceivePackFactory(
                provider,
                () -> commitConfig,
                DiffScanConfig::defaultConfig,
                SecretScanConfig::defaultConfig,
                BinaryBlobConfig::defaultConfig,
                ContentPatternConfig.defaultConfig(),
                GpgConfig.defaultConfig(),
                null,
                null,
                pushStore,
                approvalGateway,
                null,
                Duration.ofSeconds(30),
                urlRuleRegistry));
        // Mirrors FogwallServletRegistrar.registerGitServlet: serve fetches, or mount the refusing factory (#478).
        gitServlet.setUploadPackFactory(
                provider.isServeFetch() ? new ServerUploadPackFactory() : new DisabledFetchUploadPackFactory());

        String pushServletPath = PUSH_PREFIX + provider.servletPath();
        String pushMapping = pushServletPath + "/*";
        var gitHolder = new ServletHolder(gitServlet);
        gitHolder.setName("git-gitea-e2e");
        context.addServlet(gitHolder, pushMapping);
        // Mirrors FogwallServletRegistrar: without this the fixture would exercise a server mode
        // wiring production never runs, leaking every push's quarantine instead of discarding it.
        context.addFilter(
                new FilterHolder(new QuarantineCleanupFilter()), pushMapping, EnumSet.of(DispatcherType.REQUEST));
        context.addFilter(
                new FilterHolder(new SmartHttpErrorFilter()), pushMapping, EnumSet.of(DispatcherType.REQUEST));
        context.addFilter(
                new FilterHolder(new BasicAuthChallengeFilter(provider, new UpstreamAuthProbe())),
                pushMapping,
                EnumSet.of(DispatcherType.REQUEST));

        // Transparent proxy fogwallServlet on /proxy/...
        String proxyServletPath = PROXY_PREFIX + provider.servletPath();
        String proxyMapping = proxyServletPath + "/*";

        var proxyServlet = new FogwallServlet(pushStore);
        var proxyHolder = new ServletHolder(proxyServlet);
        proxyHolder.setName("proxy-gitea-e2e");
        proxyHolder.setInitParameter("proxyTo", giteaUri.toString());
        proxyHolder.setInitParameter("prefix", proxyServletPath);
        proxyHolder.setInitParameter("hostHeader", giteaUri.getHost());
        proxyHolder.setInitParameter("preserveHost", "false");
        context.addServlet(proxyHolder, proxyMapping);

        // Proxy-mode filter chain — mirrors FogwallServletRegistrar.registerCoreFilters().
        // PushStoreAuditFilter wraps the entire chain via try-finally and implements plain Filter
        // (not FogwallFilter), so it is registered first, outside the sorted list.
        String serviceUrl = "http://localhost";
        addFilter(context, proxyMapping, new PushStoreAuditFilter(pushStore));

        // Build the orderable filter list and sort by getOrder() to match production behaviour.
        // ForceGitClientFilter returns Integer.MIN_VALUE so it sorts to the top automatically.
        // Note: SecretScanningFilter and FetchStore-backed UrlRuleAggregateFilter are omitted here
        // because the fixture uses simplified configs that don't need them.
        List<FogwallFilter> filters = new ArrayList<>();
        filters.add(new ForceGitClientFilter());
        filters.add(new ParseGitRequestFilter(provider));
        filters.add(new AllowApprovedPushFilter(pushStore, serviceUrl));
        filters.add(new EnrichPushCommitsFilter(provider, proxyCache));
        filters.add(new UrlRuleAggregateFilter(100, provider, urlRuleRegistry));
        if (identityResolver != null && permissionService != null) {
            filters.add(new CheckUserPushPermissionFilter(identityResolver, permissionService));
            filters.add(new CommitAttributionPolicyFilter(identityResolver, attributionPolicyConfig));
        }
        filters.add(new CheckEmptyBranchFilter());
        filters.add(new CheckHiddenCommitsFilter());
        filters.add(new CheckAuthorEmailsFilter(commitConfig));
        filters.add(new CheckCommitMessagesFilter(commitConfig));
        filters.add(new ScanDiffFilter(DiffScanConfig.defaultConfig()));
        filters.add(new GpgSignatureFilter(GpgConfig.defaultConfig()));
        filters.add(new ValidationSummaryFilter());
        filters.add(new FetchFinalizerFilter());
        filters.add(new PushFinalizerFilter(serviceUrl, proxyGatewayFactory.apply(pushStore)));
        filters.add(new AuditLogFilter());
        filters.sort(Comparator.comparingInt(FogwallFilter::getOrder));
        for (FogwallFilter filter : filters) {
            addFilter(context, proxyMapping, filter);
        }

        server.setHandler(new BlockingContentHandler(context));
        server.start();

        port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    /** The port the proxy is listening on. */
    int getPort() {
        return port;
    }

    /** The in-memory push store, exposed for approval flow in tests. */
    PushStore getPushStore() {
        return pushStore;
    }

    /** The provider ID used by this fixture — use this when seeding permission grants. */
    String getProviderId() {
        return providerId;
    }

    String getGiteaHostPort() {
        return giteaHostPort;
    }

    String getPushBase() {
        return "http://localhost:" + port + PUSH_PREFIX + "/" + giteaHostPort;
    }

    String getProxyBase() {
        return "http://localhost:" + port + PROXY_PREFIX + "/" + giteaHostPort;
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

    /**
     * Commit validation config matching the shell-script test suite:
     *
     * <ul>
     *   <li>Author email domain must be one of the known test domains
     *   <li>Block {@code noreply}/{@code bot} local parts
     *   <li>Block WIP / fixup! / DO NOT MERGE messages
     *   <li>Block {@code password=} / {@code token=} secrets in messages
     * </ul>
     */
    static CommitConfig buildCommitConfig() {
        return CommitConfig.builder()
                .author(CommitConfig.AuthorConfig.builder()
                        .email(CommitConfig.EmailConfig.builder()
                                .rules(List.of(
                                        EmailRule.allow(
                                                EmailRule.Field.DOMAIN,
                                                EmailRule.Match.REGEX,
                                                "(proton\\.me|gmail\\.com|outlook\\.com|yahoo\\.com|example\\.com)$"),
                                        EmailRule.block(
                                                EmailRule.Field.LOCAL,
                                                EmailRule.Match.REGEX,
                                                "^(noreply|no-reply|bot|nobody)$")))
                                .build())
                        .build())
                .message(CommitConfig.MessageConfig.builder()
                        .block(BlockConfig.builder()
                                .literals(List.of("WIP", "DO NOT MERGE", "fixup!", "squash!"))
                                .patterns(List.of(Pattern.compile("(?i)(password|secret|token)\\s*[=:]\\s*\\S+")))
                                .build())
                        .build())
                .build();
    }
}
