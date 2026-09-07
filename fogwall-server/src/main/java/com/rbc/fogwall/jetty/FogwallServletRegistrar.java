package com.rbc.fogwall.jetty;

import static org.eclipse.jgit.transport.HttpTransport.setConnectionFactory;

import com.rbc.fogwall.approval.ApprovalGateway;
import com.rbc.fogwall.config.BinaryBlobConfig;
import com.rbc.fogwall.config.CommitConfig;
import com.rbc.fogwall.config.ContentPatternConfig;
import com.rbc.fogwall.config.DiffScanConfig;
import com.rbc.fogwall.config.GpgConfig;
import com.rbc.fogwall.config.JettyConfigurationBuilder;
import com.rbc.fogwall.config.ScmOAuthConfig;
import com.rbc.fogwall.config.SecretScanConfig;
import com.rbc.fogwall.config.TlsConfig;
import com.rbc.fogwall.db.FetchStore;
import com.rbc.fogwall.db.PushStore;
import com.rbc.fogwall.db.UrlRuleRegistry;
import com.rbc.fogwall.git.DisabledFetchUploadPackFactory;
import com.rbc.fogwall.git.LocalRepositoryCache;
import com.rbc.fogwall.git.ServerReceivePackFactory;
import com.rbc.fogwall.git.ServerRepositoryResolver;
import com.rbc.fogwall.git.ServerUploadPackFactory;
import com.rbc.fogwall.git.UpstreamAuthProbe;
import com.rbc.fogwall.jetty.reload.ConfigHolder;
import com.rbc.fogwall.net.ResolvedOutboundProxy;
import com.rbc.fogwall.permission.RepoPermissionService;
import com.rbc.fogwall.provider.BitbucketProvider;
import com.rbc.fogwall.provider.FogwallProvider;
import com.rbc.fogwall.provider.ForgejoProvider;
import com.rbc.fogwall.provider.GitHubProvider;
import com.rbc.fogwall.provider.GitLabProvider;
import com.rbc.fogwall.scmapi.GitHubNodeIdResolver;
import com.rbc.fogwall.scmapi.GitLabProjectIdResolver;
import com.rbc.fogwall.scmapi.GraphQlLiterals;
import com.rbc.fogwall.scmapi.ProposalContent;
import com.rbc.fogwall.scmapi.ProposalContentInspector;
import com.rbc.fogwall.service.PushIdentityResolver;
import com.rbc.fogwall.servlet.FogwallServlet;
import com.rbc.fogwall.servlet.ScmApiGraphQlForwardServlet;
import com.rbc.fogwall.servlet.ScmApiRestForwardServlet;
import com.rbc.fogwall.servlet.ScmApiRestPathPolicy;
import com.rbc.fogwall.servlet.filter.*;
import com.rbc.fogwall.tls.SslAwareHttpConnectionFactory;
import com.rbc.fogwall.tls.SslUtil;
import com.rbc.fogwall.validation.SecretScanCheck;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.ee11.servlet.FilterHolder;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jgit.http.server.GitServlet;
import tools.jackson.databind.JsonNode;

/**
 * Utility class that registers the git proxy servlets and filters onto a Jetty {@link ServletContextHandler}. Shared
 * between the standalone server ({@link FogwallJettyApplication}) and the server-with-dashboard application in
 * {@code fogwall-dashboard}.
 */
@Slf4j
public final class FogwallServletRegistrar {

    /** Canonical path prefix for server mode (fogwall terminates the git connection and acts as the git server). */
    public static final String SERVER_PATH_PREFIX = "/server";
    /**
     * Legacy path prefix for server mode, kept as a permanent-for-now alias of {@link #SERVER_PATH_PREFIX} so existing
     * git remotes keep working. Deprecated in favour of {@code /server}; slated for removal in a future major release.
     */
    public static final String PUSH_PATH_PREFIX = "/push";

    public static final String PROXY_PATH_PREFIX = "/proxy";

    /**
     * Mount point for GitHub's GraphQL dialect. {@code gh} posts every issue/PR mutation to this one path.
     *
     * @see #registerScmApiListeners for why these are absolute paths rather than a shared prefix
     */
    public static final String GITHUB_GRAPHQL_MOUNT = "/api/graphql";

    /** Mount point for GitLab's REST v4 dialect — everything {@code glab} sends lives below it. */
    public static final String GITLAB_REST_MOUNT = "/api/v4/*";

    /** Mount point for the Forgejo REST v1 dialect, shared by {@code fj} and {@code tea}. */
    public static final String FORGEJO_REST_MOUNT = "/api/v1/*";

    /**
     * Name prefix for the per-provider proposals listeners: {@code scm-api-<provider>}, where {@code <provider>} is the
     * configured instance name rather than its type — {@code gitea} and {@code codeberg} are separate listeners of the
     * one Forgejo type. Names both the connector and the forward servlet holder on it, so the two cannot drift apart.
     */
    public static final String SCM_API_CONNECTOR_PREFIX = "scm-api-";

    /**
     * Connector names for the two listeners that serve git and the dashboard.
     *
     * <p>The main context is bound to these, so it answers on them and nowhere else. A context with no virtual host
     * matches every connector, which would put the git servlets — and the dashboard — on each proposals port too: an
     * operator exposing one of those ports for {@code gh} would be exposing {@code /server} and {@code /proxy} through
     * it. Two of the proposals connectors also relax URI compliance, so the git servlets would receive encoded
     * separators the main port rejects at the parser.
     */
    public static final String MAIN_HTTP_CONNECTOR = "fogwall-http";

    public static final String MAIN_HTTPS_CONNECTOR = "fogwall-https";

    /** Virtual hosts for the main context: the two connectors above, and nothing else. */
    public static final List<String> MAIN_VIRTUAL_HOSTS =
            List.of("@" + MAIN_HTTP_CONNECTOR, "@" + MAIN_HTTPS_CONNECTOR);

    /**
     * URI compliance for the two SCM API listeners that need it: Jetty's default rejects an encoded {@code %2F} in the
     * path as an ambiguous path separator, and two dialects address something through one. GitLab names a project as a
     * single {@code owner%2Frepo} segment. Forgejo encodes a repository-relative file path into one segment of its blob
     * endpoints, which {@code fj} reads a pull request template from before creating one. In both the encoded slash is
     * load-bearing, not an evasion attempt.
     *
     * <p>Exactly one violation is allowed, rather than reaching for {@code LEGACY} or {@code UNSAFE}, and only on these
     * connectors — the git server and transparent-proxy ports keep Jetty's default. The allowlists match the raw,
     * still-encoded URI ({@code ScmApiRestPath}), so a {@code %2F} stays inside one segment where it belongs instead of
     * being decoded into an extra path element that could shift which repository is authorized.
     */
    public static final UriCompliance SCM_API_URI_COMPLIANCE =
            UriCompliance.from(EnumSet.of(UriCompliance.Violation.AMBIGUOUS_PATH_SEPARATOR));

    /**
     * Server-mode path prefixes to register each git servlet under: the canonical {@code /server} and legacy
     * {@code /push}.
     */
    private static final List<String> SERVER_PATH_PREFIXES = List.of(SERVER_PATH_PREFIX, PUSH_PATH_PREFIX);

    private FogwallServletRegistrar() {}

    /**
     * Registers git servlets, proxy servlets, and filter chains for every provider. This is the primary entry point for
     * both the standalone and dashboard applications.
     */
    public static void registerProviders(
            ServletContextHandler context,
            FogwallContext fogwallContext,
            JettyConfigurationBuilder configBuilder,
            List<FogwallProvider> providers) {
        // Wire up JGit's HTTP transport factory once for all server-mode connections
        if (fogwallContext.upstreamTls() != null) {
            setConnectionFactory(new SslAwareHttpConnectionFactory(
                    fogwallContext.upstreamTls().trustManagers()));
            log.info("Custom upstream SSL trust applied to JGit HTTP transport");
        }
        // ForceGitClientFilter is registered once at each top-level path prefix so it covers any path with the
        // right prefix, including paths that don't match a configured provider. Both server-mode prefixes
        // (/server canonical, /push legacy alias) and the transparent-proxy prefix are covered.
        var forceGitClientHolder = new FilterHolder(new ForceGitClientFilter());
        forceGitClientHolder.setAsyncSupported(true);
        context.addFilter(forceGitClientHolder, PROXY_PATH_PREFIX + "/*", EnumSet.of(DispatcherType.REQUEST));
        for (String serverPrefix : SERVER_PATH_PREFIXES) {
            context.addFilter(forceGitClientHolder, serverPrefix + "/*", EnumSet.of(DispatcherType.REQUEST));
        }

        ConfigHolder configHolder = configBuilder.buildConfigHolder();
        Supplier<CommitConfig> commitConfigSupplier = configHolder::getCommitConfig;
        Supplier<DiffScanConfig> diffScanConfigSupplier = configHolder::getDiffScanConfig;
        Supplier<SecretScanConfig> secretScanConfigSupplier = configHolder::getSecretScanConfig;
        Supplier<BinaryBlobConfig> binaryBlobConfigSupplier = configHolder::getBinaryBlobConfig;
        Supplier<ScmOAuthConfig> scmOAuthConfigSupplier = configHolder::getScmOAuthConfig;
        ContentPatternConfig contentPatternConfig = configBuilder.buildContentPatternConfig();

        // Seed config rules once — registry is the single source of truth for all rule evaluation
        fogwallContext.urlRuleRegistry().seedFromConfig(configBuilder.buildConfigRules());

        for (FogwallProvider provider : providers) {
            log.info("Registering provider: {}", provider.getName());
            if (isHttpProvider(provider)) {
                registerGitServlet(
                        context,
                        provider,
                        fogwallContext.serverCache(),
                        commitConfigSupplier,
                        diffScanConfigSupplier,
                        secretScanConfigSupplier,
                        binaryBlobConfigSupplier,
                        scmOAuthConfigSupplier,
                        contentPatternConfig,
                        fogwallContext.pushStore(),
                        fogwallContext.serviceUrl(),
                        fogwallContext.approvalGateway(),
                        fogwallContext.pushIdentityResolver(),
                        fogwallContext.repoPermissionService(),
                        fogwallContext.heartbeatIntervalSeconds(),
                        fogwallContext.approvalTimeoutSeconds(),
                        fogwallContext.failFast(),
                        fogwallContext.maxPushBytes(),
                        fogwallContext.maxObjectSizeBytes(),
                        fogwallContext.upstreamConnectTimeoutSeconds(),
                        fogwallContext.urlRuleRegistry(),
                        fogwallContext.fetchStore());
                registerProxyServlet(
                        context,
                        provider,
                        fogwallContext.pushStore(),
                        fogwallContext.proxyConnectTimeoutSeconds(),
                        fogwallContext.upstreamTls(),
                        configBuilder.getResolvedOutboundProxy());
                registerCoreFilters(
                        context,
                        provider,
                        fogwallContext.proxyCache(),
                        configBuilder,
                        commitConfigSupplier,
                        diffScanConfigSupplier,
                        secretScanConfigSupplier,
                        binaryBlobConfigSupplier,
                        contentPatternConfig,
                        fogwallContext.pushStore(),
                        fogwallContext.serviceUrl(),
                        fogwallContext.approvalGateway(),
                        fogwallContext.pushIdentityResolver(),
                        fogwallContext.repoPermissionService(),
                        fogwallContext.fetchStore(),
                        fogwallContext.urlRuleRegistry());
            } else {
                log.info(
                        "Skipping HTTP servlet registration for {} — SSH provider (scheme={})",
                        provider.getName(),
                        provider.getUri().getScheme());
            }
        }
    }

    /** Returns {@code true} when the provider URI uses an HTTP/HTTPS scheme and HTTP servlets should be registered. */
    static boolean isHttpProvider(FogwallProvider provider) {
        String scheme = provider.getUri().getScheme();
        return scheme != null && (scheme.equals("http") || scheme.equals("https"));
    }

    /**
     * Builds a {@link ServerReceivePackFactory} for the given provider using the current context and config. Used by
     * the SSH server to share the same factory the HTTP push servlet uses.
     */
    public static ServerReceivePackFactory buildReceivePackFactory(
            FogwallContext fogwallContext, JettyConfigurationBuilder configBuilder, FogwallProvider provider) {
        ConfigHolder configHolder = configBuilder.buildConfigHolder();
        var factory = new ServerReceivePackFactory(
                provider,
                configHolder::getCommitConfig,
                configHolder::getDiffScanConfig,
                configHolder::getSecretScanConfig,
                configHolder::getBinaryBlobConfig,
                configBuilder.buildContentPatternConfig(),
                GpgConfig.defaultConfig(),
                fogwallContext.repoPermissionService(),
                fogwallContext.pushIdentityResolver(),
                fogwallContext.pushStore(),
                fogwallContext.approvalGateway(),
                fogwallContext.serviceUrl(),
                Duration.ofSeconds(fogwallContext.heartbeatIntervalSeconds()),
                fogwallContext.urlRuleRegistry());
        factory.setFailFast(configBuilder.isFailFast());
        factory.setConnectTimeoutSeconds(fogwallContext.upstreamConnectTimeoutSeconds());
        factory.setApprovalTimeout(Duration.ofSeconds(fogwallContext.approvalTimeoutSeconds()));
        factory.setCache(fogwallContext.serverCache());
        factory.setSshScmIdentityEnricher(fogwallContext.sshScmIdentityEnricher());
        factory.setScmOAuthConfigSupplier(configHolder::getScmOAuthConfig);
        factory.setMaxPackBytes(fogwallContext.maxPushBytes());
        factory.setMaxObjectSizeBytes(fogwallContext.maxObjectSizeBytes());
        return factory;
    }

    public static void registerGitServlet(
            ServletContextHandler context,
            FogwallProvider provider,
            LocalRepositoryCache cache,
            Supplier<CommitConfig> commitConfigSupplier,
            Supplier<DiffScanConfig> diffScanConfigSupplier,
            Supplier<SecretScanConfig> secretScanConfigSupplier,
            Supplier<BinaryBlobConfig> binaryBlobConfigSupplier,
            Supplier<ScmOAuthConfig> scmOAuthConfigSupplier,
            ContentPatternConfig contentPatternConfig,
            PushStore pushStore,
            String serviceUrl,
            ApprovalGateway approvalGateway,
            PushIdentityResolver pushIdentityResolver,
            RepoPermissionService repoPermissionService,
            int heartbeatIntervalSeconds,
            int approvalTimeoutSeconds,
            boolean failFast,
            long maxPushBytes,
            long maxObjectSizeBytes,
            int connectTimeoutSeconds,
            UrlRuleRegistry urlRuleRegistry,
            FetchStore fetchStore) {
        // A configured GitServlet is built fresh per path prefix rather than shared: mapping one servlet instance
        // under two holders would init() it twice. The resolver/factory are cheap, and both share the one repo cache.
        Supplier<GitServlet> gitServletFactory = () -> {
            var resolver = new ServerRepositoryResolver(cache, provider);

            var factory = new ServerReceivePackFactory(
                    provider,
                    commitConfigSupplier,
                    diffScanConfigSupplier,
                    secretScanConfigSupplier,
                    binaryBlobConfigSupplier,
                    contentPatternConfig,
                    GpgConfig.defaultConfig(),
                    repoPermissionService,
                    pushIdentityResolver,
                    pushStore,
                    approvalGateway,
                    serviceUrl,
                    Duration.ofSeconds(heartbeatIntervalSeconds),
                    urlRuleRegistry);
            factory.setFailFast(failFast);
            factory.setConnectTimeoutSeconds(connectTimeoutSeconds);
            factory.setApprovalTimeout(Duration.ofSeconds(approvalTimeoutSeconds));
            factory.setCache(cache);
            factory.setScmOAuthConfigSupplier(scmOAuthConfigSupplier);
            factory.setMaxPackBytes(maxPushBytes);
            factory.setMaxObjectSizeBytes(maxObjectSizeBytes);

            var gitServlet = new GitServlet();
            gitServlet.setRepositoryResolver(resolver);
            gitServlet.setReceivePackFactory(factory);
            // Fetch toggle (#478): serve clone/fetch from the local mirror, or mount a factory that refuses
            // git-upload-pack with a clear git-side error. Push (receive-pack) is unaffected either way. Note the
            // refusing factory must be mounted explicitly — JGit's default upload-pack factory serves, so omitting
            // this would leave fetches enabled.
            gitServlet.setUploadPackFactory(
                    provider.isServeFetch() ? new ServerUploadPackFactory() : new DisabledFetchUploadPackFactory());
            return gitServlet;
        };

        // Server mode is served under both the canonical /server prefix and the legacy /push alias.
        for (String prefix : SERVER_PATH_PREFIXES) {
            String mapping = prefix + provider.servletPath() + "/*";

            var holder = new ServletHolder(gitServletFactory.get());
            holder.setName("git-" + provider.getName() + "-" + prefix.substring(1));
            context.addServlet(holder, mapping);

            // Must wrap the GitServlet: the quarantine is opened while the servlet runs, so this filter's
            // finally block is what bounds its lifetime to the request.
            context.addFilter(
                    new FilterHolder(new QuarantineCleanupFilter()), mapping, EnumSet.of(DispatcherType.REQUEST));
            context.addFilter(new FilterHolder(new LfsRejectionFilter()), mapping, EnumSet.of(DispatcherType.REQUEST));
            context.addFilter(
                    new FilterHolder(new SmartHttpErrorFilter()), mapping, EnumSet.of(DispatcherType.REQUEST));
            context.addFilter(
                    new FilterHolder(new BasicAuthChallengeFilter(provider, new UpstreamAuthProbe())),
                    mapping,
                    EnumSet.of(DispatcherType.REQUEST));
            context.addFilter(
                    new FilterHolder(new ParseGitRequestFilter(provider, maxPushBytes)),
                    mapping,
                    EnumSet.of(DispatcherType.REQUEST));
            context.addFilter(
                    new FilterHolder(new UrlRuleAggregateFilter(100, provider, fetchStore, urlRuleRegistry)),
                    mapping,
                    EnumSet.of(DispatcherType.REQUEST));

            log.info("Registered GitServlet for {} at {}", provider.getName(), mapping);
        }
    }

    public static void registerProxyServlet(
            ServletContextHandler context,
            FogwallProvider provider,
            PushStore pushStore,
            int connectTimeoutSeconds,
            SslUtil.UpstreamTls upstreamTls,
            ResolvedOutboundProxy outboundProxy) {
        String proxyPath = PROXY_PATH_PREFIX + provider.servletPath();
        String proxyMapping = proxyPath + "/*";

        var proxyServlet =
                new FogwallServlet(pushStore, upstreamTls != null ? upstreamTls.sslContext() : null, outboundProxy);
        var proxyHolder = new ServletHolder(proxyServlet);
        proxyHolder.setName("proxy-" + provider.getName());
        proxyHolder.setInitParameter("proxyTo", provider.getUri().toString());
        proxyHolder.setInitParameter("prefix", proxyPath);
        proxyHolder.setInitParameter("hostHeader", provider.getUri().getHost());
        proxyHolder.setInitParameter("preserveHost", "false");
        if (connectTimeoutSeconds > 0) {
            proxyHolder.setInitParameter("connectTimeout", String.valueOf(connectTimeoutSeconds * 1000L));
        }
        context.addServlet(proxyHolder, proxyMapping);

        log.info("Registered proxy servlet for {} at {}", provider.getName(), proxyMapping);
    }

    /**
     * Registers the SCM API proxy pipeline for one GitHub provider instance: audit filter (outermost) → authenticate →
     * gate (parse/allowlist/resolve/authorize) → forwarding servlet. Registration order is execution order — these are
     * plain {@link jakarta.servlet.Filter}s, not {@link FogwallFilter}s, since the git-specific request/step model
     * ({@code GitRequestDetails}, {@code PushStep}) doesn't apply to GraphQL traffic.
     */
    public static void registerScmApiProxy(
            ServletContextHandler context,
            GitHubProvider provider,
            FogwallContext fogwallContext,
            ProposalContentInspector contentInspector,
            boolean requireKnownCli) {
        String mapping = GITHUB_GRAPHQL_MOUNT;

        var nodeIdResolver = new GitHubNodeIdResolver(fogwallContext.gitHubNodeIdCache());

        addFilter(context, mapping, new ScmApiAuditFilter(fogwallContext.scmApiActionStore()));
        addFilter(context, mapping, new ScmApiAuthenticateFilter(provider, fogwallContext.pushIdentityResolver()));
        addFilter(context, mapping, new ScmApiUserAgentFilter(requireKnownCli));
        addFilter(
                context,
                mapping,
                new ScmApiGitHubGateFilter(provider, nodeIdResolver, fogwallContext.repoPermissionService()));
        addFilter(
                context,
                mapping,
                new ScmApiContentInspectionFilter(
                        contentInspector, ProposalContent::fromGraphQlBody, FogwallServletRegistrar::graphQlLiterals));

        var forwardHolder = new ServletHolder(new ScmApiGraphQlForwardServlet(provider.getGraphqlUrl()));
        forwardHolder.setName(SCM_API_CONNECTOR_PREFIX + provider.getName());
        context.addServlet(forwardHolder, mapping);

        log.info("Registered SCM API proxy for {} at {}", provider.getName(), mapping);
    }

    /**
     * Registers the SCM API proxy pipeline for one GitLab provider instance: audit filter (outermost) → authenticate →
     * gate (REST allowlist, path-based authorization — no node-ID resolution, GitLab addresses its target directly in
     * the URL) → REST forwarding servlet. See {@link ScmApiGitLabGateFilter}'s javadoc for how this differs from
     * GitHub's GraphQL pipeline.
     */
    public static void registerScmApiProxyGitLab(
            ServletContextHandler context,
            GitLabProvider provider,
            FogwallContext fogwallContext,
            ProposalContentInspector contentInspector,
            boolean requireKnownCli) {
        String mapping = GITLAB_REST_MOUNT;

        addFilter(context, mapping, new ScmApiAuditFilter(fogwallContext.scmApiActionStore()));
        addFilter(context, mapping, new ScmApiAuthenticateFilter(provider, fogwallContext.pushIdentityResolver()));
        addFilter(context, mapping, new ScmApiUserAgentFilter(requireKnownCli));
        addFilter(
                context,
                mapping,
                new ScmApiGitLabGateFilter(
                        provider,
                        new GitLabProjectIdResolver(fogwallContext.gitLabProjectIdCache()),
                        fogwallContext.repoPermissionService()));
        addFilter(
                context,
                mapping,
                new ScmApiContentInspectionFilter(
                        contentInspector, ProposalContent::fromGitLabBody, body -> List.of()));

        var forwardHolder = new ServletHolder(new ScmApiRestForwardServlet(
                provider.getApiUrl(), ScmApiRestPathPolicy.EncodedSeparators.GITLAB_PROJECT_SEGMENT));
        forwardHolder.setName(SCM_API_CONNECTOR_PREFIX + provider.getName());
        context.addServlet(forwardHolder, mapping);

        log.info("Registered SCM API proxy for {} at {}", provider.getName(), mapping);
    }

    /**
     * Registers the SCM API proxy pipeline for one Gitea/Forgejo provider instance. Same shape as
     * {@link #registerScmApiProxyGitLab} — path-addressed REST, no node-ID resolution — differing only in the allowlist
     * table. One registration serves both {@code tea} and {@code fj}: they speak the same server API, so
     * {@link ScmApiForgejoGateFilter} covers the union of the endpoints each CLI uses.
     */
    public static void registerScmApiProxyForgejo(
            ServletContextHandler context,
            ForgejoProvider provider,
            FogwallContext fogwallContext,
            ProposalContentInspector contentInspector,
            boolean requireKnownCli) {
        String mapping = FORGEJO_REST_MOUNT;

        addFilter(context, mapping, new ScmApiAuditFilter(fogwallContext.scmApiActionStore()));
        addFilter(context, mapping, new ScmApiAuthenticateFilter(provider, fogwallContext.pushIdentityResolver()));
        addFilter(context, mapping, new ScmApiUserAgentFilter(requireKnownCli));
        addFilter(context, mapping, new ScmApiForgejoGateFilter(provider, fogwallContext.repoPermissionService()));
        addFilter(
                context,
                mapping,
                new ScmApiContentInspectionFilter(
                        contentInspector, ProposalContent::fromForgejoBody, body -> List.of()));

        var forwardHolder = new ServletHolder(new ScmApiRestForwardServlet(
                provider.getApiUrl(), ScmApiRestPathPolicy.EncodedSeparators.FORGEJO_FILE_PATH));
        forwardHolder.setName(SCM_API_CONNECTOR_PREFIX + provider.getName());
        context.addServlet(forwardHolder, mapping);

        log.info("Registered SCM API proxy for {} at {}", provider.getName(), mapping);
    }

    /**
     * Gives every SCM-API-enabled provider its own listener, with its dialect mounted at the root of a context bound to
     * that listener via Jetty's {@code "@connectorName"} virtual-host form.
     *
     * <p>A port per provider is not a stylistic choice — it is the only shape the CLIs can be pointed at. {@code gh}
     * and {@code fj} address the API from the host root and discard any path prefix (verified against both binaries;
     * {@code fj} resolves {@code base.join("/api/v1/...")}, which RFC 3986 makes replace the whole base path), so a
     * shared {@code /scm-api/<provider>} prefix is unreachable for them. Nor can the dialects simply share one root
     * listener: two GitLab instances would both claim {@code /api/v4}, and two Gitea/Forgejo instances {@code /api/v1}.
     *
     * <p>Clients are then configured with nothing but a host and port — {@code GH_HOST}, {@code GITLAB_HOST},
     * {@code tea login add --url}, {@code fj -H} — which is the one form all four accept.
     */
    public static void registerScmApiListeners(
            Server server,
            ContextHandlerCollection contexts,
            FogwallContext fogwallContext,
            JettyConfigurationBuilder configBuilder,
            List<FogwallProvider> providers)
            throws Exception {
        TlsConfig tls = configBuilder.getTlsConfig();
        boolean serveTls = tls.isServerTlsConfigured();
        // The same content rules that guard a push, applied to the prose a proposal publishes upstream.
        ConfigHolder scmApiConfigHolder = configBuilder.buildConfigHolder();
        var proposalsBlock = configBuilder.buildProposalsBlockConfig();
        var proposalsContentPatterns = configBuilder.buildContentPatternConfig();
        var contentInspector = new ProposalContentInspector(
                () -> proposalsBlock,
                scmApiConfigHolder::getSecretScanConfig,
                new SecretScanCheck(scmApiConfigHolder.getSecretScanConfig()),
                () -> proposalsContentPatterns);
        var plaintextListeners = new ArrayList<String>();

        for (FogwallProvider provider : providers) {
            if (!isHttpProvider(provider) || !configBuilder.isProposalsEnabled(provider)) continue;
            if (!(provider instanceof GitHubProvider
                    || provider instanceof GitLabProvider
                    || provider instanceof ForgejoProvider)) {
                log.warn(
                        "SCM API proxy enabled for {} but no dialect is implemented for provider type {} — skipping",
                        provider.getName(),
                        provider.getType());
                continue;
            }

            int port = configBuilder.getProposalsPort(provider);
            boolean requireKnownCli = configBuilder.isProposalsRequireKnownCli(provider);
            String connectorName = SCM_API_CONNECTOR_PREFIX + provider.getName();
            // GitLab and Gitea/Forgejo both address something through an encoded separator, so both relax Jetty's
            // URI compliance; GitHub keeps the strict default and rejects one at the parser. The relaxation is only
            // the connector's half — ScmApiRestPathPolicy then confines where a %2F may appear per dialect, which is
            // where the actual restriction lives. Removing that policy would leave these two listeners open.
            boolean allowEncodedSeparator = provider instanceof GitLabProvider || provider instanceof ForgejoProvider;

            // Inherited from server.tls, not configured per provider: the certificate is per-hostname and these
            // listeners differ only by port, so one set of material covers them all.
            var http = new HttpConnectionFactory(scmApiHttpConfiguration(allowEncodedSeparator));
            var connector = serveTls
                    ? new ServerConnector(
                            server,
                            new SslConnectionFactory(JettyTls.serverSslContextFactory(tls), http.getProtocol()),
                            http)
                    : new ServerConnector(server, http);
            connector.setPort(port);
            connector.setName(connectorName);
            server.addConnector(connector);
            if (!serveTls) {
                plaintextListeners.add(provider.getName() + ":" + port);
            }

            var context = scmApiContext(connectorName, allowEncodedSeparator);

            if (provider instanceof GitHubProvider githubProvider) {
                registerScmApiProxy(context, githubProvider, fogwallContext, contentInspector, requireKnownCli);
            } else if (provider instanceof GitLabProvider gitlabProvider) {
                registerScmApiProxyGitLab(context, gitlabProvider, fogwallContext, contentInspector, requireKnownCli);
            } else {
                registerScmApiProxyForgejo(
                        context, (ForgejoProvider) provider, fogwallContext, contentInspector, requireKnownCli);
            }

            contexts.addHandler(context);
            log.info(
                    "SCM API listener for {} on port {} ({})",
                    provider.getName(),
                    port,
                    serveTls ? "https, inherited from server.tls" : "http");
        }

        // The CLIs address a custom host over HTTPS and offer no way to ask for http, so a plaintext listener is
        // reachable only through something that terminates TLS in front of it. fogwall cannot see whether an ingress
        // or load balancer is doing that, so it says what it knows rather than assuming either way.
        if (!plaintextListeners.isEmpty()) {
            log.warn(
                    "Proposals listeners are serving plain HTTP ({}) because server.tls is not configured. gh, glab,"
                            + " tea and fj all address a custom host over HTTPS, so TLS must terminate somewhere in"
                            + " front of these ports — an ingress, route, or load balancer. Configure server.tls to"
                            + " have fogwall terminate it instead.",
                    String.join(", ", plaintextListeners));
        }
    }

    /**
     * The GraphQL query's own literals, unescaped by the parser. A GraphQL request wraps its query in JSON, so decoding
     * the transport still leaves GraphQL-level escaping in place, and arguments inlined in the query text never appear
     * as JSON values at all.
     */
    private static List<String> graphQlLiterals(JsonNode requestBody) {
        var query = requestBody.get("query");
        return query == null || !query.isString() ? List.of() : GraphQlLiterals.from(query.asString());
    }

    /**
     * {@link HttpConfiguration} for one SCM API listener. {@link #SCM_API_URI_COMPLIANCE} is applied only when
     * {@code allowEncodedSeparator} is set — on the GitLab and Forgejo listeners, the two dialects that address
     * something through an encoded separator. The GitHub listener keeps Jetty's strict default and refuses one at the
     * parser, before any fogwall code runs.
     *
     * <p>Relaxing it here only gets the request past the parser. {@code ScmApiRestPathPolicy} decides where a
     * {@code %2F} is actually permitted, per dialect, and is what keeps the relaxation narrow.
     */
    public static HttpConfiguration scmApiHttpConfiguration(boolean allowEncodedSeparator) {
        var httpConfig = new HttpConfiguration();
        if (allowEncodedSeparator) {
            httpConfig.setUriCompliance(SCM_API_URI_COMPLIANCE);
        }
        return httpConfig;
    }

    /**
     * The root context for one SCM API listener, bound to {@code connectorName} so the dialect owns that port and only
     * that port.
     *
     * <p>Encoded path separators have to be permitted <b>twice</b>. {@link #SCM_API_URI_COMPLIANCE} gets GitLab's
     * {@code /projects/owner%2Frepo} past the HTTP parser; the servlet layer then rejects it again, handing the request
     * an {@code AmbiguousURI} wrapper whose {@code getServletPath()} and {@code getPathInfo()} throw 400 — which
     * {@code ScmApiRestPath} calls, so every {@code glab} request fails before a filter sees it. Relaxing it here is
     * safe precisely because the allowlists and the forwarder read the raw URI and never the decoded path this permits.
     */
    public static ServletContextHandler scmApiContext(String connectorName, boolean allowEncodedSeparator) {
        var context = new ServletContextHandler("/", false, false);
        context.setVirtualHosts(List.of("@" + connectorName));
        if (allowEncodedSeparator) {
            context.getServletHandler().setDecodeAmbiguousURIs(true);
        }
        return context;
    }

    private static void addFilter(ServletContextHandler context, String mapping, Filter filter) {
        var holder = new FilterHolder(filter);
        holder.setAsyncSupported(true);
        context.addFilter(holder, mapping, EnumSet.of(DispatcherType.REQUEST));
    }

    /**
     * Registers the core proxy filter chain for the given provider. Covers all content validation including
     * {@link AllowApprovedPushFilter}, which is harmless in standalone mode because no push records are ever set to
     * {@code APPROVED} via the transparent-proxy re-push flow when running without a dashboard.
     */
    public static void registerCoreFilters(
            ServletContextHandler context,
            FogwallProvider provider,
            LocalRepositoryCache repositoryCache,
            JettyConfigurationBuilder configBuilder,
            Supplier<CommitConfig> commitConfigSupplier,
            Supplier<DiffScanConfig> diffScanConfigSupplier,
            Supplier<SecretScanConfig> secretScanConfigSupplier,
            Supplier<BinaryBlobConfig> binaryBlobConfigSupplier,
            ContentPatternConfig contentPatternConfig,
            PushStore pushStore,
            String serviceUrl,
            ApprovalGateway approvalGateway,
            PushIdentityResolver pushIdentityResolver,
            RepoPermissionService repoPermissionService,
            FetchStore fetchStore,
            UrlRuleRegistry urlRuleRegistry) {
        String urlPattern = PROXY_PATH_PREFIX + provider.servletPath() + "/*";

        // PushStoreAuditFilter wraps the entire chain via try-finally; must be registered first.
        // Ahead of everything, including PushStoreAuditFilter: an LFS upload is refused before any body is read
        // and before a push record is opened for a request fogwall will not process.
        var lfsRejectionHolder = new FilterHolder(new LfsRejectionFilter());
        lfsRejectionHolder.setAsyncSupported(true);
        context.addFilter(lfsRejectionHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        var pushStoreAuditFilterHolder = new FilterHolder(new PushStoreAuditFilter(pushStore));
        pushStoreAuditFilterHolder.setAsyncSupported(true);
        context.addFilter(pushStoreAuditFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        // Build the orderable filter list. Sorted by getOrder() before registration so the Jetty chain
        // execution order matches the documented order ranges in fogwallFilter.
        List<FogwallFilter> filters = new ArrayList<>();
        filters.add(new ParseGitRequestFilter(provider, configBuilder.getMaxPushBytes()));
        filters.add(new EnrichPushCommitsFilter(provider, repositoryCache, configBuilder.getMaxObjectSizeBytes()));
        filters.add(new AllowApprovedPushFilter(pushStore, serviceUrl));

        filters.add(new UrlRuleAggregateFilter(100, provider, fetchStore, urlRuleRegistry));

        if (provider instanceof BitbucketProvider bitbucketProvider) {
            filters.add(new BitbucketIdentityFilter(bitbucketProvider));
        }
        filters.add(new CheckUserPushPermissionFilter(pushIdentityResolver, repoPermissionService));
        filters.add(new CommitAttributionPolicyFilter(pushIdentityResolver, commitConfigSupplier));
        filters.add(new CheckEmptyBranchFilter());
        filters.add(new CheckHiddenCommitsFilter());
        filters.add(new CheckAuthorEmailsFilter(commitConfigSupplier));
        filters.add(new CheckTrailersFilter(commitConfigSupplier));
        filters.add(new CheckCommitMessagesFilter(commitConfigSupplier));
        filters.add(new ContentPatternMessageFilter(contentPatternConfig));
        filters.add(new BinaryBlobFilter(binaryBlobConfigSupplier));
        filters.add(new ScanDiffFilter(diffScanConfigSupplier));
        filters.add(new SecretScanningFilter(secretScanConfigSupplier));
        filters.add(new ContentPatternDiffFilter(contentPatternConfig));
        filters.add(new GpgSignatureFilter(GpgConfig.defaultConfig()));
        filters.add(new ValidationSummaryFilter());
        filters.add(new FetchFinalizerFilter());
        filters.add(new PushFinalizerFilter(serviceUrl, approvalGateway));
        filters.add(new AuditLogFilter());

        boolean failFast = configBuilder != null && configBuilder.isFailFast();
        if (failFast) {
            filters.forEach(f -> {
                if (f instanceof AbstractFogwallFilter af) af.setFailFast(true);
            });
        }

        filters.sort(Comparator.comparingInt(FogwallFilter::getOrder));

        for (FogwallFilter filter : filters) {
            var holder = new FilterHolder(filter);
            holder.setAsyncSupported(true);
            context.addFilter(holder, urlPattern, EnumSet.of(DispatcherType.REQUEST));
        }

        log.info("Registered {} proxy filters for provider {}", filters.size(), provider.getName());
    }
}
