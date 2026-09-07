package com.rbc.fogwall.config;

import com.rbc.fogwall.approval.ApprovalGateway;
import com.rbc.fogwall.approval.AutoApprovalGateway;
import com.rbc.fogwall.approval.UiApprovalGateway;
import com.rbc.fogwall.db.CompositeUrlRuleRegistry;
import com.rbc.fogwall.db.FetchStore;
import com.rbc.fogwall.db.MongoStoreFactory;
import com.rbc.fogwall.db.PushStore;
import com.rbc.fogwall.db.PushStoreFactory;
import com.rbc.fogwall.db.ScmApiActionStore;
import com.rbc.fogwall.db.ScmApiActionStoreFactory;
import com.rbc.fogwall.db.UrlRuleRegistry;
import com.rbc.fogwall.db.jdbc.DataSourceFactory;
import com.rbc.fogwall.db.jdbc.JdbcFetchStore;
import com.rbc.fogwall.db.jdbc.JdbcUrlRuleRegistry;
import com.rbc.fogwall.db.memory.InMemoryUrlRuleRegistry;
import com.rbc.fogwall.db.model.AccessRule;
import com.rbc.fogwall.db.model.MatchTarget;
import com.rbc.fogwall.db.model.MatchType;
import com.rbc.fogwall.git.LocalRepositoryCache;
import com.rbc.fogwall.jetty.FogwallContext;
import com.rbc.fogwall.jetty.reload.ConfigHolder;
import com.rbc.fogwall.jetty.reload.LiveConfigLoader;
import com.rbc.fogwall.net.FogwallHttpExecutor;
import com.rbc.fogwall.net.OutboundProxyResolver;
import com.rbc.fogwall.net.OutboundProxySystemProperties;
import com.rbc.fogwall.net.ResolvedOutboundProxy;
import com.rbc.fogwall.permission.GroupPermissionRule;
import com.rbc.fogwall.permission.GroupPermissionStore;
import com.rbc.fogwall.permission.JdbcGroupPermissionStore;
import com.rbc.fogwall.permission.JdbcRepoPermissionStore;
import com.rbc.fogwall.permission.PermissionGroup;
import com.rbc.fogwall.permission.PermissionStore;
import com.rbc.fogwall.permission.RepoPermission;
import com.rbc.fogwall.permission.RepoPermissionService;
import com.rbc.fogwall.provider.*;
import com.rbc.fogwall.scmapi.GitHubNodeIdCache;
import com.rbc.fogwall.scmapi.GitLabProjectIdCache;
import com.rbc.fogwall.scmapi.JdbcGitHubNodeIdCache;
import com.rbc.fogwall.scmapi.JdbcGitLabProjectIdCache;
import com.rbc.fogwall.service.CachingTokenPushIdentityResolver;
import com.rbc.fogwall.service.JdbcScmTokenCache;
import com.rbc.fogwall.service.JdbcSshFingerprintCache;
import com.rbc.fogwall.service.PushIdentityResolver;
import com.rbc.fogwall.service.ScmTokenCache;
import com.rbc.fogwall.service.SshFingerprintCache;
import com.rbc.fogwall.service.SshScmIdentityEnricher;
import com.rbc.fogwall.service.TokenPushIdentityResolver;
import com.rbc.fogwall.ssh.SshKeyUtils;
import com.rbc.fogwall.tls.SslUtil;
import com.rbc.fogwall.user.CompositeUserStore;
import com.rbc.fogwall.user.JdbcUserStore;
import com.rbc.fogwall.user.ReadOnlyUserStore;
import com.rbc.fogwall.user.ScmIdentity;
import com.rbc.fogwall.user.SshKeyEntry;
import com.rbc.fogwall.user.StaticUserStore;
import com.rbc.fogwall.user.UserEntry;
import com.rbc.fogwall.user.UserStore;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.storage.file.WindowCacheConfig;

/**
 * Constructs runtime objects ({@link FogwallProvider}, {@link CommitConfig}, {@link PushStore}, etc.) from the parsed
 * {@link FogwallConfig}. All map-drilling and type-unsafe casting is gone — this class now just reads typed fields and
 * constructs objects.
 */
@Slf4j
public class JettyConfigurationBuilder {

    private final FogwallConfig config;
    private List<FogwallProvider> cachedProviders;
    private ProviderRegistry cachedProviderRegistry;
    private DataSource cachedDataSource;
    private MongoStoreFactory cachedMongoStoreFactory;
    private PushStore cachedPushStore;
    private FetchStore cachedFetchStore;
    private UserStore cachedUserStore;
    private ScmTokenCache cachedTokenCache;
    private RepoPermissionService cachedRepoPermissionService;
    private GroupPermissionStore cachedGroupPermissionStore;
    private UrlRuleRegistry cachedUrlRuleRegistry;
    private ConfigHolder cachedConfigHolder;
    private ResolvedOutboundProxy cachedOutboundProxy;
    private GitHubNodeIdCache cachedGitHubNodeIdCache;
    private GitLabProjectIdCache cachedGitLabProjectIdCache;
    private ScmApiActionStore cachedScmApiActionStore;

    public JettyConfigurationBuilder(FogwallConfig config) {
        this.config = config;
    }

    /** Returns the configured server port. */
    public int getServerPort() {
        return config.getServer().getPort();
    }

    /** Returns the heartbeat interval in seconds (0 = disabled). */
    public int getHeartbeatIntervalSeconds() {
        return config.getServer().getHeartbeatIntervalSeconds();
    }

    /** Returns the server mode approval wait timeout in seconds. */
    public int getApprovalTimeoutSeconds() {
        return config.getServer().getApprovalTimeoutSeconds();
    }

    /** Returns whether fail-fast validation is enabled (stop after first failure). */
    public boolean isFailFast() {
        return config.getServer().isFailFast();
    }

    /** Returns the virtual-thread admission limit (0 = virtual-thread dispatch disabled). */
    public int getMaxConcurrentRequests() {
        return config.getServer().getMaxConcurrentRequests();
    }

    /** Returns the platform thread-pool sizing ({@code server.threads.*}). */
    public ServerConfig.ThreadsConfig getThreadsConfig() {
        return config.getServer().getThreads();
    }

    /** Returns the maximum accepted request body size in bytes (0 = no configured limit). */
    public long getMaxPushBytes() {
        return config.getServer().getMaxPushBytes();
    }

    /** Returns the maximum decompressed size of a single pushed object in bytes (0 = no configured limit). */
    public long getMaxObjectSizeBytes() {
        return config.getServer().getMaxObjectSizeBytes();
    }

    /** Returns the server mode upstream connect timeout in seconds (0 = no timeout). */
    public int getUpstreamConnectTimeoutSeconds() {
        return config.getServer().getUpstreamConnectTimeoutSeconds();
    }

    /** Returns the transparent-proxy connect timeout in seconds (0 = no timeout). */
    public int getProxyConnectTimeoutSeconds() {
        return config.getServer().getProxyConnectTimeoutSeconds();
    }

    /**
     * Resolves {@code server.outbound-proxy} (with env-var fallback). Result is cached — the underlying config is only
     * re-read on a full process restart, not hot-reloaded.
     */
    public ResolvedOutboundProxy getResolvedOutboundProxy() {
        if (cachedOutboundProxy == null) {
            cachedOutboundProxy =
                    OutboundProxyResolver.resolve(config.getServer().getOutboundProxy());
        }
        return cachedOutboundProxy;
    }

    /**
     * Applies the resolved outbound proxy at the JVM level (JGit Transport system properties/Authenticator/JAAS) and to
     * the shared HC5 client used by provider REST API calls. Call once at startup, before any outbound connection is
     * made. The Jetty HttpClient path is wired separately per-servlet via {@link #getResolvedOutboundProxy()}.
     */
    public void applyOutboundProxySystemWiring() {
        var resolved = getResolvedOutboundProxy();
        OutboundProxySystemProperties.apply(resolved);
        FogwallHttpExecutor.configure(resolved);
    }

    /**
     * Returns the live {@link ConfigHolder} pre-populated with the initial commit config. All filters and hooks that
     * support live reload receive a {@code Supplier<CommitConfig>} backed by this holder. When {@link LiveConfigLoader}
     * fires a reload it calls {@link ConfigHolder#update} on the same instance, so all in-flight and future pushes
     * immediately see the new config.
     */
    public ConfigHolder buildConfigHolder() {
        if (cachedConfigHolder == null) {
            cachedConfigHolder = new ConfigHolder(
                    buildCommitConfig(),
                    buildDiffScanConfig(),
                    buildSecretScanConfig(),
                    buildBinaryBlobConfig(),
                    buildAttestations(config),
                    buildScmOAuthConfig());
        }
        return cachedConfigHolder;
    }

    /**
     * Builds the global attestation-questions list from the top-level {@code attestations:} YAML section. Used at
     * startup and during hot-reload. Per-provider variants are not supported in this release.
     */
    public List<AttestationQuestion> buildAttestations(FogwallConfig cfg) {
        if (cfg.getAttestations() == null) return List.of();
        return List.copyOf(cfg.getAttestations());
    }

    /** Returns the {@link ReloadConfig} from the parsed config file. */
    public ReloadConfig getReloadConfig() {
        return config.getReload();
    }

    /** Returns the service URL for dashboard links, defaulting to {@code http://localhost:<port>/dashboard}. */
    public String getServiceUrl() {
        String url = config.getServer().getServiceUrl();
        return (url != null && !url.isBlank()) ? url : "http://localhost:" + getServerPort() + "/dashboard";
    }

    /** Creates the list of enabled providers from configuration. Result is cached. */
    public List<FogwallProvider> buildProviders() {
        if (cachedProviders != null) return cachedProviders;
        var providers = config.getProviders().entrySet().stream()
                .peek(entry -> {
                    if (!entry.getValue().isEnabled()) {
                        log.info("Provider '{}' is disabled, skipping", entry.getKey());
                    }
                })
                .filter(entry -> entry.getValue().isEnabled())
                .map(e -> createProvider(e.getKey(), e.getValue()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();

        if (providers.isEmpty()) {
            log.warn("No providers configured. Add providers to fogwall.yml to enable proxying.");
        }
        cachedProviders = providers;
        return cachedProviders;
    }

    /** Builds and caches a {@link ProviderRegistry} keyed by provider name. */
    public ProviderRegistry buildProviderRegistry() {
        if (cachedProviderRegistry != null) return cachedProviderRegistry;
        Map<String, FogwallProvider> byName = new LinkedHashMap<>();
        buildProviders().forEach(p -> byName.put(p.getName(), p));
        cachedProviderRegistry = new InMemoryProviderRegistry(byName);
        return cachedProviderRegistry;
    }

    /**
     * Validates all cross-references to providers in the loaded config — {@code permissions:}, {@code rules:}, and
     * {@code users.scm-identities:} — against the configured providers list. Call this immediately after constructing
     * the builder (before any DB or server setup) so the app crashes with a clear message rather than failing later
     * deep in the startup sequence.
     *
     * <p>This is a read-only pass: it builds the {@link ProviderRegistry} (cheap) and checks every reference, but
     * writes nothing and opens no resources.
     *
     * @throws IllegalStateException if any provider reference cannot be resolved
     */
    public void validateProviderReferences() {
        buildProviderRegistry(); // warm the cache

        // Detect duplicate servlet paths among HTTP providers — two providers with the same
        // host:port would map to the same /push/<host>:<port>/* and /proxy/<host>:<port>/* paths,
        // causing Jetty to throw IllegalStateException at startup.
        var httpProviders = buildProviders().stream()
                .filter(p -> {
                    String scheme = p.getUri().getScheme();
                    return scheme != null && (scheme.equals("http") || scheme.equals("https"));
                })
                .toList();
        var seenPaths = new HashSet<String>();
        for (var provider : httpProviders) {
            String path = provider.servletPath();
            if (!seenPaths.add(path)) {
                throw new IllegalStateException(
                        "Multiple HTTP providers share the same upstream host:port or path-suffix '"
                                + path
                                + "'. Each HTTP provider must have a unique URI. "
                                + "SSH providers sharing a host:port are allowed since they use separate transport.");
            }
        }

        config.getUsers()
                .forEach(uc -> uc.getScmIdentities().forEach(s -> {
                    if (!"proxy".equals(s.getProvider())) {
                        resolveProviderName("User '" + uc.getUsername() + "' scm-identity", s.getProvider());
                    }
                }));

        config.getPermissions()
                .forEach(p -> resolveProviderName("Permission for user '" + p.getUsername() + "'", p.getProvider()));

        config.getRules()
                .getAllow()
                .forEach(rule -> resolveProviderName("ALLOW rule (order=" + rule.getOrder() + ")", rule.getProvider()));

        config.getRules()
                .getDeny()
                .forEach(rule -> resolveProviderName("DENY rule (order=" + rule.getOrder() + ")", rule.getProvider()));

        log.debug(
                "Provider reference validation passed ({} users, {} permissions, {} allow rules, {} deny rules)",
                config.getUsers().size(),
                config.getPermissions().size(),
                config.getRules().getAllow().size(),
                config.getRules().getDeny().size());
    }

    /**
     * Validates that {@code name} refers to a configured provider and returns it. Returns {@code null} for null/blank
     * input (meaning "applies to all providers"). Throws {@link IllegalStateException} on startup if unknown —
     * misconfiguration must be caught early.
     */
    private String resolveProviderName(String context, String name) {
        if (name == null || name.isBlank()) return null;
        return buildProviderRegistry()
                .resolveProvider(name)
                .orElseThrow(() -> {
                    String known = buildProviderRegistry().getProviders().stream()
                            .map(p -> "'" + p.getName() + "'")
                            .collect(Collectors.joining(", "));
                    return new IllegalStateException(String.format(
                            "%s references unknown provider '%s'. "
                                    + "Use the provider name from providers: config (e.g. 'github'). "
                                    + "Configured providers: %s",
                            context, name, known));
                })
                .getName();
    }

    /**
     * Builds all config-sourced URL access rules (both allow and deny). Rules are provider-scoped via the
     * {@code provider} field — {@code null} means the rule applies to all providers. Call
     * {@link UrlRuleRegistry#seedFromConfig} with the result at startup.
     */
    public List<AccessRule> buildConfigRules() {
        List<AccessRule> rules = new ArrayList<>();
        appendAccessRules(rules, config.getRules().getAllow(), AccessRule.Access.ALLOW);
        appendAccessRules(rules, config.getRules().getDeny(), AccessRule.Access.DENY);
        return rules;
    }

    /**
     * Builds a {@link CommitConfig} from the {@code commit:} YAML section. Pattern strings are compiled here; absent or
     * blank strings produce permissive defaults (no restriction).
     */
    @SuppressWarnings("deprecation") // intentionally reads the deprecated identity-verification alias to warn on it
    public CommitConfig buildCommitConfig() {
        CommitSettings cs = config.getCommit();

        CommitConfig.AuthorConfig authorConfig =
                buildAuthorConfig(cs.getAuthor().getEmail());
        CommitConfig.CommitterConfig committerConfig =
                buildCommitterConfig(cs.getCommitter().getEmail());

        CommitConfig.MessageConfig messageConfig = CommitConfig.MessageConfig.builder()
                .block(buildBlockConfig(cs.getMessage().getBlock()))
                .build();

        if (cs.getIdentityVerification() != null) {
            log.warn(
                    "Config key 'commit.identity-verification' is deprecated and IGNORED — its values have no effect. "
                            + "Rename it to 'commit.attribution-policy' (see docs/CONFIGURATION.md#commit-attribution-policy).");
        }
        CommitSettings.CommitAttributionPolicySettings ivs = cs.getAttributionPolicy();
        CommitConfig.CommitAttributionPolicyConfig attributionPolicyConfig =
                CommitConfig.CommitAttributionPolicyConfig.builder()
                        .committer(CommitConfig.CommitAttributionPolicyMode.fromString(ivs.getCommitter()))
                        .author(CommitConfig.CommitAttributionPolicyMode.fromString(ivs.getAuthor()))
                        .build();

        CommitConfig commitConfig = CommitConfig.builder()
                .attributionPolicy(attributionPolicyConfig)
                .author(authorConfig)
                .committer(committerConfig)
                .message(messageConfig)
                .trailers(buildTrailerPolicyConfig(cs.getTrailers()))
                .build();

        log.info(
                "Loaded commit config: committer.domain.allow={}, committer.local.block={}, message.literals={}, message.patterns={}",
                cs.getCommitter().getEmail().getDomain().getAllow(),
                cs.getCommitter().getEmail().getLocal().getBlock(),
                commitConfig.getMessage().getBlock().getLiterals().size(),
                commitConfig.getMessage().getBlock().getPatterns().size());

        return commitConfig;
    }

    /** Builds a {@link ScmOAuthConfig} from the {@code scm-oauth:} YAML section. */
    public ScmOAuthConfig buildScmOAuthConfig() {
        ScmOAuthSettings settings = config.getScmOauth();
        ScmOAuthConfig scmOAuthConfig = ScmOAuthConfig.builder()
                .identityMode(ScmOAuthConfig.IdentityMode.fromString(settings.getIdentityMode()))
                .build();
        log.info("Loaded SCM OAuth config: identityMode={}", scmOAuthConfig.getIdentityMode());
        return scmOAuthConfig;
    }

    private CommitConfig.AuthorConfig buildAuthorConfig(CommitSettings.EmailSettings email) {
        return CommitConfig.AuthorConfig.builder()
                .email(buildEmailConfig(email))
                .build();
    }

    private CommitConfig.CommitterConfig buildCommitterConfig(CommitSettings.EmailSettings email) {
        return CommitConfig.CommitterConfig.builder()
                .email(buildEmailConfig(email))
                .build();
    }

    private CommitConfig.EmailConfig buildEmailConfig(CommitSettings.EmailSettings email) {
        List<EmailRule> rules = new ArrayList<>();

        // Current shape: explicit allow/block rules.
        for (CommitSettings.RuleSettings r : email.getRules()) {
            rules.add(new EmailRule(
                    EmailRule.Action.fromString(r.getAction()),
                    EmailRule.Field.fromString(r.getField()),
                    EmailRule.Match.fromString(r.getMatch()),
                    r.getValue()));
        }

        // Deprecated aliases: domain.allow / local.block, folded into equivalent regex rules (fogwall#146).
        String domainAllow = email.getDomain().getAllow();
        if (domainAllow != null && !domainAllow.isBlank()) {
            warnDeprecatedEmailKey("domain.allow", "action: allow, field: domain, match: regex");
            rules.add(EmailRule.allow(EmailRule.Field.DOMAIN, EmailRule.Match.REGEX, domainAllow));
        }
        String localBlock = email.getLocal().getBlock();
        if (localBlock != null && !localBlock.isBlank()) {
            warnDeprecatedEmailKey("local.block", "action: block, field: local, match: regex");
            rules.add(EmailRule.block(EmailRule.Field.LOCAL, EmailRule.Match.REGEX, localBlock));
        }

        return CommitConfig.EmailConfig.builder().rules(rules).build();
    }

    private void warnDeprecatedEmailKey(String oldKey, String replacement) {
        log.warn(
                "Config key 'commit...email.{}' is deprecated — express it as a 'rules' entry instead "
                        + "({}). The old key is still applied for now; migrate to the unified rules list "
                        + "(see docs/CONFIGURATION.md#commit-email-policy).",
                oldKey,
                replacement);
    }

    private CommitConfig.TrailerPolicyConfig buildTrailerPolicyConfig(CommitSettings.TrailersSettings trailers) {
        CommitSettings.SignedOffBySettings sob = trailers.getSignedOffBy();
        CommitSettings.CoAuthoredBySettings cab = trailers.getCoAuthoredBy();
        return CommitConfig.TrailerPolicyConfig.builder()
                .signedOffBy(CommitConfig.SignedOffByConfig.builder()
                        .require(sob.isRequire())
                        .requireAuthorMatch(sob.isRequireAuthorMatch())
                        .build())
                .coAuthoredBy(CommitConfig.CoAuthoredByConfig.builder()
                        .policy(CommitConfig.CoAuthorPolicy.fromString(cab.getPolicy()))
                        .email(buildEmailConfig(cab.getEmail()))
                        .build())
                .build();
    }

    /**
     * Builds the {@link DiffScanConfig} from {@code diff-scan:} in fogwall.yml. Compiles literal and regex-pattern
     * block lists applied against push diff added-lines.
     */
    public DiffScanConfig buildDiffScanConfig() {
        DiffScanConfig cfg = DiffScanConfig.builder()
                .block(buildBlockConfig(config.getDiffScan().getBlock()))
                .build();
        log.info(
                "Loaded diff-scan config: literals={}, patterns={}",
                cfg.getBlock().getLiterals().size(),
                cfg.getBlock().getPatterns().size());
        return cfg;
    }

    /** Builds the {@link BinaryBlobConfig} from {@code binary-blob:} in fogwall.yml. */
    public BinaryBlobConfig buildBinaryBlobConfig() {
        BinaryBlobSettings bb = config.getBinaryBlob();
        BinaryBlobConfig cfg = BinaryBlobConfig.builder()
                .enabled(bb.isEnabled())
                .maxSizeBytes(bb.getMaxSizeBytes())
                .denyMimeTypes(new ArrayList<>(bb.getDenyMimeTypes()))
                .build();
        log.info(
                "Loaded binary-blob config: enabled={}, maxSizeBytes={}, denyMimeTypes={}",
                cfg.isEnabled(),
                cfg.getMaxSizeBytes(),
                cfg.getDenyMimeTypes().size());
        return cfg;
    }

    /** Builds the {@link ContentPatternConfig} from {@code content-patterns:} in fogwall.yml. */
    public ContentPatternConfig buildContentPatternConfig() {
        ContentPatternSettings cp = config.getContentPatterns();
        ContentPatternConfig cfg = ContentPatternConfig.builder()
                .enabled(cp.isEnabled())
                .bundles(new ArrayList<>(cp.getBundles()))
                .scanDiff(cp.isScanDiff())
                .scanCommitMessages(cp.isScanCommitMessages())
                .scanProposals(cp.isScanProposals())
                .build();
        log.info(
                "Loaded content-patterns config: enabled={}, bundles={}, scanDiff={}, scanCommitMessages={}, scanProposals={}",
                cfg.isEnabled(),
                cfg.getBundles(),
                cfg.isScanDiff(),
                cfg.isScanCommitMessages(),
                cfg.isScanProposals());
        return cfg;
    }

    /** Builds the {@link SecretScanConfig} from {@code secret-scan:} in fogwall.yml. */
    public SecretScanConfig buildSecretScanConfig() {
        SecretScanSettings ss = config.getSecretScan();
        String inlineConfig = ss.getInlineConfig();
        String configFile = ss.getConfigFile();
        if (inlineConfig != null && !inlineConfig.isBlank() && configFile != null && !configFile.isBlank()) {
            log.warn("secret-scan: both inline-config and config-file are set — inline-config takes precedence");
        }
        SecretScanConfig cfg = SecretScanConfig.builder()
                .enabled(ss.isEnabled())
                .autoInstall(ss.isAutoInstall())
                .installDir(ss.getInstallDir())
                .version(ss.getVersion())
                .scannerPath(ss.getScannerPath())
                .configFile(configFile)
                .inlineConfig(inlineConfig)
                .timeoutSeconds(ss.getTimeoutSeconds())
                .build();
        log.info("Loaded secret-scan config: enabled={}", cfg.isEnabled());
        return cfg;
    }

    /**
     * Tunes JGit's process-global pack-window cache for a long-running, many-mirror server rather than JGit's
     * desktop-git defaults. This is the read-side counterpart to fogwall's push-side DoS guards
     * ({@code maxPushBytes}/{@code maxObjectSize}): those bound what a client can push <em>through</em> the gateway,
     * whereas these bound how efficiently JGit serves and mirrors content it already holds.
     *
     * <ul>
     *   <li><b>packedGitLimit</b> 10&nbsp;MB&nbsp;&rarr;&nbsp;64&nbsp;MB — the memory budget (a cap, filled lazily) for
     *       cached pack windows across the whole process. JGit's 10&nbsp;MB default is sized for one repo on a laptop;
     *       a server holding many mirrors evicts and re-reads pack windows constantly at that size.
     *   <li><b>packedGitOpenFiles</b> 128&nbsp;&rarr;&nbsp;256 — max pack files kept open at once. A gateway in front
     *       of many repos, each with several packs, thrashes descriptors at 128.
     *   <li><b>packedGitMMAP</b> left <b>false</b> — deliberately. True mmap can SIGBUS if a pack file is replaced
     *       while mapped, which is exactly the concurrent-refresh scenario the serve path is intentionally left
     *       lock-free for (see {@link com.rbc.fogwall.git.LocalRepositoryCache}); byte-array windows are safe under
     *       that race.
     *   <li><b>streamFileThreshold</b> left at the JGit default (50&nbsp;MB) — already a sane heap guard for large
     *       blobs read out of a mirror.
     * </ul>
     *
     * <p>These are deliberately <b>not</b> exposed as fogwall config keys: they are engine-tuning internals almost no
     * operator should reason about, and keeping them out of the config file preserves its user-facing clarity. If a
     * deployment ever needs to override them (e.g. a memory-constrained pod dialing {@code packedGitLimit} down), add a
     * hidden environment variable rather than a config key. WindowCache is a JVM singleton, so this reconfigure is
     * process-global and idempotent — calling it again simply re-applies the same values.
     */
    private static void tuneGitPackCacheForServerWorkload() {
        WindowCacheConfig cfg = new WindowCacheConfig();
        cfg.setPackedGitLimit(64 * 1024 * 1024);
        cfg.setPackedGitOpenFiles(256);
        cfg.setPackedGitMMAP(false);
        cfg.install();
        log.info("Tuned JGit pack cache for server workload: packedGitLimit=64MB, packedGitOpenFiles=256, mmap=false");
    }

    /**
     * Builds the complete {@link FogwallContext} using the config-derived {@link ApprovalGateway} (based on
     * {@code server.approval-mode}).
     */
    public FogwallContext buildProxyContext() throws IOException {
        PushStore ps = buildPushStore();
        return buildProxyContextWith(buildApprovalGateway(ps));
    }

    /**
     * Builds the complete {@link FogwallContext} with a caller-supplied {@link ApprovalGateway}. Used by the dashboard
     * application, which always forces {@link UiApprovalGateway} regardless of config.
     */
    public FogwallContext buildProxyContext(ApprovalGateway approvalGateway) throws IOException {
        return buildProxyContextWith(approvalGateway);
    }

    private FogwallContext buildProxyContextWith(ApprovalGateway approvalGateway) throws IOException {
        tuneGitPackCacheForServerWorkload();
        PushStore ps = buildPushStore();
        FetchStore fs = buildFetchStore();
        UserStore us = buildUserStore();
        UrlRuleRegistry rr = buildUrlRuleRegistry();
        // Mirror clone depth is configurable per mode (#476). Server mode defaults to full history, transparent
        // proxy to a shallow clone; shallow-since (a time boundary) takes precedence over clone-depth when both are
        // set.
        var cacheConfig = config.getCache();
        var serverCacheConfig = cacheConfig.getServer();
        var serverCache = new LocalRepositoryCache(
                Files.createTempDirectory("fogwall-server-"),
                serverCacheConfig.resolveCloneDepth(CacheConfig.DEFAULT_SERVER_CLONE_DEPTH),
                true,
                serverCacheConfig.resolveShallowSince());
        var proxyCacheConfig = cacheConfig.getProxy();
        var proxyCache = new LocalRepositoryCache(
                Files.createTempDirectory("fogwall-cache-"),
                proxyCacheConfig.resolveCloneDepth(CacheConfig.DEFAULT_PROXY_CLONE_DEPTH),
                true,
                proxyCacheConfig.resolveShallowSince());
        return new FogwallContext(
                ps,
                fs,
                us,
                rr,
                buildRepoPermissionService(),
                buildPushIdentityResolver(us),
                approvalGateway,
                buildCommitConfig(),
                getServiceUrl(),
                getHeartbeatIntervalSeconds(),
                getApprovalTimeoutSeconds(),
                isFailFast(),
                getMaxPushBytes(),
                getMaxObjectSizeBytes(),
                getUpstreamConnectTimeoutSeconds(),
                getProxyConnectTimeoutSeconds(),
                serverCache,
                proxyCache,
                buildUpstreamTls(),
                buildProviderRegistry(),
                new SshScmIdentityEnricher(SshScmIdentityEnricher.DEFAULT_TTL, buildSshFingerprintCache()),
                buildNodeIdCache(),
                buildGitLabProjectIdCache(),
                buildScmApiActionStore());
    }

    /**
     * Builds the upstream {@link SslUtil.UpstreamTls} from the configured CA bundle, or returns {@code null} if no
     * custom trust is configured (JVM defaults will be used).
     */
    public SslUtil.UpstreamTls buildUpstreamTls() {
        var tls = config.getServer().getTls();
        if (!tls.isUpstreamTrustConfigured()) {
            return null;
        }
        try {
            Path bundle = Path.of(tls.getTrustCaBundle());
            SslUtil.UpstreamTls result = SslUtil.buildUpstreamTls(bundle);
            log.info("Loaded upstream CA trust bundle from {}", bundle);
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load upstream CA bundle: " + tls.getTrustCaBundle(), e);
        }
    }

    /** Returns the TLS config for the server listener. */
    public TlsConfig getTlsConfig() {
        return config.getServer().getTls();
    }

    /** Builds a {@link PermissionStore} backed by the configured database. */
    public PermissionStore<RepoPermission> buildRepoPermissionStore() {
        String type = config.getDatabase().getType();
        if ("mongo".equals(type)) {
            return requireMongoStoreFactory().repoPermissionStore();
        }
        return new JdbcRepoPermissionStore(requireJdbcDataSource());
    }

    /**
     * Builds and caches the {@link RepoPermissionService}. CONFIG-sourced entries from the {@code permissions:} YAML
     * section are seeded on first call; subsequent calls return the same instance.
     */
    public RepoPermissionService buildRepoPermissionService() {
        if (cachedRepoPermissionService != null) return cachedRepoPermissionService;
        PermissionStore<RepoPermission> store = buildRepoPermissionStore();
        store.initialize();
        cachedGroupPermissionStore = buildGroupPermissionStore();
        cachedGroupPermissionStore.initialize();
        cachedRepoPermissionService = new RepoPermissionService(store, cachedGroupPermissionStore);

        List<RepoPermission> configPerms = buildConfigPermissions(config);
        ensurePermissionUsersExist(configPerms);
        cachedRepoPermissionService.seedFromConfig(configPerms);

        seedConfigGroups(config, cachedGroupPermissionStore);

        log.info("RepoPermissionService initialized with {} config permission(s)", configPerms.size());
        return cachedRepoPermissionService;
    }

    public GroupPermissionStore buildGroupPermissionStore() {
        if (cachedGroupPermissionStore != null) return cachedGroupPermissionStore;
        DatabaseConfig db = config.getDatabase();
        if ("mongo".equals(db.getType())) {
            return requireMongoStoreFactory().groupPermissionStore();
        }
        return new JdbcGroupPermissionStore(requireJdbcDataSource());
    }

    private void ensurePermissionUsersExist(List<RepoPermission> permissions) {
        UserStore us = buildUserStore();
        permissions.stream().map(RepoPermission::getUsername).distinct().forEach(us::upsertUser);
    }

    /**
     * Creates the {@link ApprovalGateway} based on the {@code server.approval-mode} config key.
     *
     * <ul>
     *   <li>{@code auto} (default) — immediately approves every clean push; no dashboard required
     *   <li>{@code ui} — polls the push store waiting for a human reviewer via the REST API
     *   <li>{@code servicenow} — delegates to a ServiceNow approval workflow
     * </ul>
     */
    public ApprovalGateway buildApprovalGateway(PushStore pushStore) {
        String mode = config.getServer().getApprovalMode();
        return switch (mode) {
            case "ui" -> {
                log.info("Approval mode: ui (push store polling)");
                yield new UiApprovalGateway(pushStore);
            }
            default -> {
                if (!"auto".equals(mode)) {
                    log.warn("Unknown approval-mode '{}', defaulting to 'auto'", mode);
                } else {
                    log.info("Approval mode: auto (no human review required)");
                }
                yield new AutoApprovalGateway(pushStore);
            }
        };
    }

    /** Creates a {@link PushStore} based on the database configuration. */
    public PushStore buildPushStore() {
        if (cachedPushStore != null) return cachedPushStore;
        DatabaseConfig db = config.getDatabase();
        log.info("Initializing push store: type={}", db.getType());
        cachedPushStore = switch (db.getType()) {
            case "h2-mem", "h2-file", "postgres", "mysql", "mariadb" ->
                PushStoreFactory.fromDataSource(requireJdbcDataSource());
            case "mongo" -> requireMongoStoreFactory().pushStore();
            default ->
                throw new IllegalArgumentException("Unknown database type: " + db.getType()
                        + ". Supported: h2-mem, h2-file, postgres, mysql, mariadb, mongo");
        };
        return cachedPushStore;
    }

    /**
     * Builds the list of CONFIG-sourced {@link AccessRule}s from the {@code rules:} YAML section. Used both at startup
     * (seeding the registry) and during hot-reload (re-seeding via {@link UrlRuleRegistry#seedFromConfig}).
     */
    public List<AccessRule> buildConfigRules(FogwallConfig cfg) {
        List<AccessRule> rules = new ArrayList<>();
        appendAccessRules(rules, cfg.getRules().getAllow(), AccessRule.Access.ALLOW);
        appendAccessRules(rules, cfg.getRules().getDeny(), AccessRule.Access.DENY);
        return rules;
    }

    /**
     * Builds the list of CONFIG-sourced {@link RepoPermission}s from the {@code permissions:} YAML section. Used both
     * at startup (seeding the service) and during hot-reload.
     */
    public List<RepoPermission> buildConfigPermissions(FogwallConfig cfg) {
        return cfg.getPermissions().stream()
                .map(p -> {
                    String resolvedId =
                            resolveProviderName("Permission for user '" + p.getUsername() + "'", p.getProvider());
                    MatchConfig m = p.getMatch();
                    return (RepoPermission) RepoPermission.builder()
                            .username(p.getUsername())
                            .provider(resolvedId)
                            .target(MatchTarget.valueOf(m.getTarget().toUpperCase()))
                            .value(m.getValue())
                            .matchType(MatchType.valueOf((m.getType() != null ? m.getType() : "GLOB").toUpperCase()))
                            .grant(RepoPermission.Grant.valueOf(p.getGrant().toUpperCase()))
                            .source(RepoPermission.Source.CONFIG)
                            .build();
                })
                .toList();
    }

    private void seedConfigGroups(FogwallConfig cfg, GroupPermissionStore groupStore) {
        // Clear existing CONFIG-sourced groups and re-seed from YAML.
        groupStore.findAllGroups().stream()
                .filter(g -> g.getSource() == PermissionGroup.Source.CONFIG)
                .forEach(g -> groupStore.deleteGroup(g.getId()));

        UserStore us = buildUserStore();
        for (GroupConfig gc : cfg.getGroups()) {
            PermissionGroup group = PermissionGroup.builder()
                    .name(gc.getName())
                    .description(gc.getDescription())
                    .source(PermissionGroup.Source.CONFIG)
                    .build();
            groupStore.saveGroup(group);

            for (String member : gc.getMembers()) {
                us.upsertUser(member);
                groupStore.addMember(group.getId(), member);
            }

            for (GroupConfig.GroupGrantConfig grant : gc.getGrants()) {
                String resolvedProvider = resolveProviderName("Group '" + gc.getName() + "'", grant.getProvider());
                MatchConfig m = grant.getMatch();
                GroupPermissionRule rule = GroupPermissionRule.builder()
                        .groupId(group.getId())
                        .provider(resolvedProvider)
                        .target(MatchTarget.valueOf(m.getTarget().toUpperCase()))
                        .value(m.getValue())
                        .matchType(MatchType.valueOf((m.getType() != null ? m.getType() : "GLOB").toUpperCase()))
                        .grant(RepoPermission.Grant.valueOf(grant.getGrant().toUpperCase()))
                        .build();
                groupStore.saveRule(rule);
            }
        }
        log.info("Seeded {} permission group(s) from config", cfg.getGroups().size());
    }

    public UrlRuleRegistry buildUrlRuleRegistry() {
        if (cachedUrlRuleRegistry != null) return cachedUrlRuleRegistry;
        // CONFIG rules live only in memory — never written to DB, no stale duplicates on restart.
        InMemoryUrlRuleRegistry configRegistry = new InMemoryUrlRuleRegistry();
        buildConfigRules(config).forEach(configRegistry::save);

        String type = config.getDatabase().getType();
        UrlRuleRegistry dbRegistry;
        if ("mongo".equals(type)) {
            dbRegistry = requireMongoStoreFactory().repoRegistry();
        } else {
            dbRegistry = new JdbcUrlRuleRegistry(requireJdbcDataSource());
        }

        cachedUrlRuleRegistry = new CompositeUrlRuleRegistry(configRegistry, dbRegistry);
        cachedUrlRuleRegistry.initialize();
        log.info(
                "RepoRegistry initialized ({} config rules, {} db rules)",
                configRegistry.findAll().size(),
                dbRegistry.findAll().size());
        return cachedUrlRuleRegistry;
    }

    /** Builds a {@link FetchStore}. JDBC backends share the same {@link DataSource} as the push store. */
    public FetchStore buildFetchStore() {
        if (cachedFetchStore != null) return cachedFetchStore;
        String type = config.getDatabase().getType();
        FetchStore store;
        if ("mongo".equals(type)) {
            store = requireMongoStoreFactory().fetchStore();
        } else {
            store = new JdbcFetchStore(requireJdbcDataSource());
        }
        store.initialize();
        cachedFetchStore = store;
        return cachedFetchStore;
    }

    private void appendAccessRules(List<AccessRule> result, List<RuleConfig> rules, AccessRule.Access access) {
        int position = 0;
        for (RuleConfig rule : rules) {
            if (!rule.isEnabled()) continue;
            int resolvedOrder = rule.getOrder() != null ? rule.getOrder() : position * 100;
            position++;
            AccessRule.Operation ops = toOperations(rule.getOperation());
            String rawProvider = rule.getProvider().isBlank() ? null : rule.getProvider();
            String resolvedId = resolveProviderName(access.name() + " rule (order=" + resolvedOrder + ")", rawProvider);
            MatchConfig m = rule.getMatch();
            result.add(AccessRule.builder()
                    .provider(resolvedId)
                    .target(MatchTarget.valueOf(m.getTarget().toUpperCase()))
                    .value(m.getValue())
                    .matchType(MatchType.valueOf((m.getType() != null ? m.getType() : "GLOB").toUpperCase()))
                    .access(access)
                    .operation(ops)
                    .source(AccessRule.Source.CONFIG)
                    .ruleOrder(resolvedOrder)
                    .build());
        }
    }

    private static AccessRule.Operation toOperations(String ops) {
        if (ops == null || ops.isBlank()) return AccessRule.Operation.BOTH;
        return switch (ops.toUpperCase()) {
            case "FETCH" -> AccessRule.Operation.FETCH;
            case "PUSH" -> AccessRule.Operation.PUSH;
            default -> AccessRule.Operation.BOTH;
        };
    }

    /** Builds a {@link UserStore} from config. JDBC backends share the same {@link DataSource} as the push store. */
    public UserStore buildUserStore() {
        if (cachedUserStore != null) return cachedUserStore;
        List<UserEntry> staticUsers = config.getUsers().stream()
                .map(uc -> {
                    List<ScmIdentity> scmIdentities = new ArrayList<>();
                    uc.getScmIdentities().stream()
                            .map(s -> {
                                // "proxy" is a synthetic provider for push-username lookup — no resolution needed
                                String resolvedProvider = "proxy".equals(s.getProvider())
                                        ? "proxy"
                                        : resolveProviderName(
                                                "User '" + uc.getUsername() + "' scm-identity", s.getProvider());
                                return ScmIdentity.builder()
                                        .provider(resolvedProvider)
                                        .username(s.getUsername())
                                        .build();
                            })
                            .forEach(scmIdentities::add);
                    // push-usernames are stored as SCM identities under the synthetic "proxy" provider.
                    // Reserved for SCM providers (e.g. Bitbucket) that cannot return a login from a token alone.
                    uc.getPushUsernames().stream()
                            .map(pushName -> ScmIdentity.builder()
                                    .provider("proxy")
                                    .username(pushName)
                                    .build())
                            .forEach(scmIdentities::add);
                    List<String> roles = uc.getRoles().isEmpty() ? List.of("USER") : uc.getRoles();
                    List<SshKeyEntry> sshKeys = new ArrayList<>();
                    for (SshKeyConfig keyConf : uc.getSshKeys()) {
                        try {
                            String fp = SshKeyUtils.fingerprint(keyConf.getPublicKey());
                            String normalised = SshKeyUtils.normalise(keyConf.getPublicKey());
                            String comment = keyConf.getPublicKey().trim().split("\\s+").length > 2
                                    ? keyConf.getPublicKey().trim().split("\\s+")[2]
                                    : null;
                            String label = keyConf.getLabel().isBlank()
                                    ? (comment != null ? comment : "config")
                                    : keyConf.getLabel();
                            sshKeys.add(SshKeyEntry.builder()
                                    .id("config:" + fp)
                                    .username(uc.getUsername())
                                    .fingerprint(fp)
                                    .publicKey(normalised)
                                    .label(label)
                                    .createdAt(Instant.EPOCH)
                                    .locked(true)
                                    .build());
                        } catch (Exception e) {
                            log.warn("Invalid SSH key for user '{}', skipping: {}", uc.getUsername(), e.getMessage());
                        }
                    }
                    return UserEntry.builder()
                            .username(uc.getUsername())
                            .passwordHash(uc.getPasswordHash())
                            .emails(uc.getEmails())
                            .scmIdentities(scmIdentities)
                            .sshKeys(sshKeys)
                            .roles(roles)
                            .build();
                })
                .toList();

        String type = config.getDatabase().getType();
        if ("mongo".equals(type)) {
            cachedTokenCache = buildTokenCache();
            var mongoStore = requireMongoStoreFactory().userStore(cachedTokenCache);
            var configStore = new StaticUserStore(staticUsers);
            log.info("Using composite user store ({} config users + MongoDB)", staticUsers.size());
            cachedUserStore = new CompositeUserStore(configStore, mongoStore);
        } else {
            cachedTokenCache = buildTokenCache();
            var jdbcStore = new JdbcUserStore(requireJdbcDataSource(), cachedTokenCache);
            var configStore = new StaticUserStore(staticUsers);
            log.info("Using composite user store ({} config users + JDBC)", staticUsers.size());
            cachedUserStore = new CompositeUserStore(configStore, jdbcStore);
        }
        return cachedUserStore;
    }

    /**
     * Builds the {@link PushIdentityResolver}. When users are configured, returns a token-based resolver that calls the
     * SCM provider API to map a PAT to an SCM login, then looks up the proxy user via SCM identity. Returns null when
     * no users are configured (open/permissive mode).
     *
     * <p>HTTP Basic-auth username is intentionally NOT used for identity resolution — it is an unverifiable claim and
     * would violate compliance guarantees. Bitbucket is a known exception (the Bitbucket API does not return a login
     * from a token alone) and must be handled separately if/when Bitbucket support is added.
     *
     * <p>For JDBC backends, the token resolver is wrapped with {@link CachingTokenPushIdentityResolver} to avoid
     * repeated SCM API calls for the same token. The cache max age defaults to 7 days and can be overridden via the
     * {@code FOGWALL_SCM_CACHE_MAX_AGE_DAYS} environment variable.
     */
    public PushIdentityResolver buildPushIdentityResolver(ReadOnlyUserStore userStore) {
        if (config.getUsers().isEmpty()) return null;

        PushIdentityResolver tokenResolver = new TokenPushIdentityResolver(userStore);

        ScmTokenCache tokenCache = cachedTokenCache != null ? cachedTokenCache : buildTokenCache();
        tokenResolver = new CachingTokenPushIdentityResolver(tokenResolver, tokenCache, userStore);

        return tokenResolver;
    }

    private SshFingerprintCache buildSshFingerprintCache() {
        Duration ttl = SshScmIdentityEnricher.DEFAULT_TTL;
        log.info("SSH fingerprint cache enabled (TTL {} days)", ttl.toDays());
        if ("mongo".equals(config.getDatabase().getType())) {
            return requireMongoStoreFactory().sshFingerprintCache(ttl);
        }
        return new JdbcSshFingerprintCache(requireJdbcDataSource(), ttl);
    }

    private ScmTokenCache buildTokenCache() {
        long maxAgeDays = Optional.ofNullable(System.getenv("FOGWALL_SCM_CACHE_MAX_AGE_DAYS"))
                .map(Long::parseLong)
                .orElse(7L);
        Duration maxAge = Duration.ofDays(maxAgeDays);
        log.info("SCM token identity cache enabled (max age {} days)", maxAgeDays);
        if ("mongo".equals(config.getDatabase().getType())) {
            return requireMongoStoreFactory().tokenCache(maxAge);
        }
        return new JdbcScmTokenCache(requireJdbcDataSource(), maxAge);
    }

    /**
     * Builds the {@link GitHubNodeIdCache} for the SCM API proxy — resolves an opaque GraphQL node ID to
     * {@code owner/repo}. The TTL is a security parameter (see {@link ProposalsSettings#getNodeIdCacheTtl()}), not just
     * a perf knob, so unlike the pack-window cache it is deliberately operator-configurable.
     */
    public GitHubNodeIdCache buildNodeIdCache() {
        if (cachedGitHubNodeIdCache != null) return cachedGitHubNodeIdCache;
        Duration ttl = Duration.parse(config.getProposals().getNodeIdCacheTtl());
        log.info("Proposal node-ID cache enabled (TTL {})", ttl);
        cachedGitHubNodeIdCache = "mongo".equals(config.getDatabase().getType())
                ? requireMongoStoreFactory().nodeIdCache(ttl)
                : new JdbcGitHubNodeIdCache(requireJdbcDataSource(), ttl);
        return cachedGitHubNodeIdCache;
    }

    /**
     * Builds the {@link GitLabProjectIdCache} — resolves a GitLab numeric project ID to {@code owner/repo}, which is
     * what lets a fork merge request be authorized against the upstream it targets rather than the fork in its URL.
     * Shares {@code proposals.node-id-cache-ttl}: both caches map a provider-scoped opaque ID to a repository, and the
     * TTL is a security parameter for the same reason in each.
     */
    public GitLabProjectIdCache buildGitLabProjectIdCache() {
        if (cachedGitLabProjectIdCache != null) return cachedGitLabProjectIdCache;
        Duration ttl = Duration.parse(config.getProposals().getNodeIdCacheTtl());
        cachedGitLabProjectIdCache = "mongo".equals(config.getDatabase().getType())
                ? requireMongoStoreFactory().gitLabProjectIdCache(ttl)
                : new JdbcGitLabProjectIdCache(requireJdbcDataSource(), ttl);
        return cachedGitLabProjectIdCache;
    }

    /** Builds the {@link ScmApiActionStore} (the SCM API proxy audit trail) based on the database configuration. */
    public ScmApiActionStore buildScmApiActionStore() {
        if (cachedScmApiActionStore != null) return cachedScmApiActionStore;
        cachedScmApiActionStore = "mongo".equals(config.getDatabase().getType())
                ? requireMongoStoreFactory().scmApiActionStore()
                : ScmApiActionStoreFactory.fromDataSource(requireJdbcDataSource());
        return cachedScmApiActionStore;
    }

    /**
     * Whether proposals are enabled for {@code provider} — {@code providers.<name>.proposals.enabled}. Opt-in per
     * provider, default {@code false}.
     */
    public boolean isProposalsEnabled(FogwallProvider provider) {
        ProviderConfig providerConfig = config.getProviders().get(provider.getName());
        return providerConfig != null && providerConfig.getProposals().isEnabled();
    }

    /**
     * The dedicated listener port for {@code provider}'s proposal dialect — {@code providers.<name>.proposals.port}.
     * Required whenever proposals are enabled for that provider, since the dialect is mounted at the root of its own
     * listener rather than under a shared path prefix (see {@link ProposalsProviderSettings#getPort()}).
     *
     * @throws IllegalStateException if proposals are enabled for this provider without a port, rather than silently
     *     starting a listener the CLIs can never reach.
     */
    public int getProposalsPort(FogwallProvider provider) {
        ProviderConfig providerConfig = config.getProviders().get(provider.getName());
        int port = providerConfig == null ? 0 : providerConfig.getProposals().getPort();
        if (port <= 0) {
            throw new IllegalStateException("providers." + provider.getName()
                    + ".proposals.enabled is true but proposals.port is not set — proposals need a dedicated port"
                    + " because gh and fj address the API from the host root");
        }
        return port;
    }

    /**
     * Whether this provider refuses callers that aren't a recognised SCM CLI —
     * {@code providers.<name>.proposals.require-known-cli}, default {@code false}. Subtractive hardening only; see
     * {@link ProposalsProviderSettings#isRequireKnownCli()}.
     */
    public boolean isProposalsRequireKnownCli(FogwallProvider provider) {
        ProviderConfig providerConfig = config.getProviders().get(provider.getName());
        return providerConfig != null && providerConfig.getProposals().isRequireKnownCli();
    }

    private MongoStoreFactory requireMongoStoreFactory() {
        if (cachedMongoStoreFactory == null) {
            DatabaseConfig db = config.getDatabase();
            cachedMongoStoreFactory = new MongoStoreFactory(db.getUrl(), mongoDbName(db));
        }
        return cachedMongoStoreFactory;
    }

    /**
     * Returns the JDBC {@link DataSource} for JDBC-backed database types ({@code h2-mem}, {@code h2-file},
     * {@code postgres}). Returns {@code null} for {@code mongo} — callers that need a DataSource for features like
     * session persistence should check for null and either skip or fail gracefully.
     */
    public DataSource getJdbcDataSourceOrNull() {
        if ("mongo".equals(config.getDatabase().getType())) return null;
        return requireJdbcDataSource();
    }

    /**
     * Returns the shared {@link MongoStoreFactory} if {@code database.type=mongo}, else {@code null}. Callers that need
     * a {@link com.mongodb.client.MongoClient} (e.g. the dashboard's session store wiring) should go through this
     * accessor so the underlying connection pool is reused across stores.
     */
    public MongoStoreFactory getMongoStoreFactoryOrNull() {
        if (!"mongo".equals(config.getDatabase().getType())) return null;
        return requireMongoStoreFactory();
    }

    private DataSource requireJdbcDataSource() {
        if (cachedDataSource == null) {
            DatabaseConfig db = config.getDatabase();
            var pool = db.getPool();
            log.info(
                    "Database pool: maximumPoolSize={} minimumIdle={} connectionTimeout={}ms idleTimeout={}ms maxLifetime={}ms",
                    pool.getMaximumPoolSize(),
                    pool.getMinimumIdle() >= 0 ? pool.getMinimumIdle() : "(default: matches maximumPoolSize)",
                    pool.getConnectionTimeout(),
                    pool.getIdleTimeout(),
                    pool.getMaxLifetime());
            cachedDataSource = switch (db.getType()) {
                case "h2-mem" -> DataSourceFactory.h2InMemory(db.getName(), pool);
                case "h2-file" ->
                    DataSourceFactory.h2File(db.getPath().isBlank() ? "./.data/" + db.getName() : db.getPath(), pool);
                case "postgres" -> {
                    if (!db.getUrl().isBlank()) {
                        log.info("Postgres: using connection URL (individual host/port/name fields ignored)");
                        yield DataSourceFactory.fromUrl(db.getUrl(), db.getUsername(), db.getPassword(), pool);
                    }
                    yield DataSourceFactory.postgres(
                            db.getHost(), db.getPort(), db.getName(), db.getUsername(), db.getPassword(), pool);
                }
                case "mysql" -> {
                    if (!db.getUrl().isBlank()) {
                        log.info("MySQL: using connection URL (individual host/port/name fields ignored)");
                        yield DataSourceFactory.fromUrl(db.getUrl(), db.getUsername(), db.getPassword(), pool);
                    }
                    warnIfDefaultPostgresPort(db, "mysql");
                    yield DataSourceFactory.mysql(
                            db.getHost(), db.getPort(), db.getName(), db.getUsername(), db.getPassword(), pool);
                }
                case "mariadb" -> {
                    if (!db.getUrl().isBlank()) {
                        log.info("MariaDB: using connection URL (individual host/port/name fields ignored)");
                        yield DataSourceFactory.fromUrl(db.getUrl(), db.getUsername(), db.getPassword(), pool);
                    }
                    warnIfDefaultPostgresPort(db, "mariadb");
                    yield DataSourceFactory.mariadb(
                            db.getHost(), db.getPort(), db.getName(), db.getUsername(), db.getPassword(), pool);
                }
                default -> throw new IllegalStateException("No JDBC DataSource for db type: " + db.getType());
            };
        }
        return cachedDataSource;
    }

    /**
     * {@code database.port} defaults to PostgreSQL's port (5432). Warn when that untouched default is about to be used
     * for MySQL/MariaDB, which normally listen on 3306 — likely an operator oversight, not intent.
     */
    private static void warnIfDefaultPostgresPort(DatabaseConfig db, String type) {
        if (db.getPort() == 5432) {
            log.warn(
                    "database.port is left at the PostgreSQL default (5432) for database.type={}; set it explicitly"
                            + " (typically 3306) unless {} is actually listening on 5432.",
                    type,
                    type);
        }
    }

    /**
     * Resolves the MongoDB database name. If {@code name} is non-blank, uses it directly. Otherwise attempts to extract
     * the database name from the URI path (e.g. {@code mongodb://host/mydb} → {@code mydb}), falling back to
     * {@code "fogwall"}.
     */
    private static String mongoDbName(DatabaseConfig db) {
        String name = db.getName();
        if (!name.isBlank()) return name;
        try {
            String path = URI.create(db.getUrl()).getPath();
            if (path != null && path.length() > 1) {
                String extracted = path.substring(1);
                int q = extracted.indexOf('?');
                return q >= 0 ? extracted.substring(0, q) : extracted;
            }
        } catch (Exception ignored) {
        }
        return "fogwall";
    }

    private Optional<FogwallProvider> createProvider(String name, ProviderConfig providerConfig) {
        Optional<FogwallProvider> provider = buildProvider(name, providerConfig);
        // Resolve the fetch toggle (#478) after construction: a per-provider serve-fetch override wins over the
        // global server.serve-fetch default. Carried on the provider so both transports (HTTP registrar, SSH command)
        // read one source of truth.
        provider.ifPresent(p -> {
            if (p instanceof AbstractFogwallProvider afp) {
                afp.setServeFetch(resolveServeFetch(providerConfig));
            }
        });
        return provider;
    }

    /**
     * Resolves the effective serve-fetch value for a provider: per-provider override if set, else the global default.
     */
    private boolean resolveServeFetch(ProviderConfig providerConfig) {
        Boolean override = providerConfig.getServeFetch();
        return override != null ? override : config.getServer().isServeFetch();
    }

    private Optional<FogwallProvider> buildProvider(String name, ProviderConfig providerConfig) {
        String explicitType = providerConfig.getType();
        // Use explicit type if set; otherwise accept only exact built-in names, not fuzzy name inference.
        String resolvedType = (explicitType != null && !explicitType.isBlank())
                ? explicitType.toLowerCase().trim()
                : name.toLowerCase();

        String uri = providerConfig.getUri();
        String pathSuffix = providerConfig.getPathSuffix();
        URI parsedUri = (uri != null && !uri.isBlank()) ? URI.create(uri) : null;
        if (parsedUri != null && "ssh".equals(parsedUri.getScheme())) {
            log.warn(
                    "Provider '{}': a top-level 'uri' with an ssh:// scheme no longer enables SSH transport (#531). "
                            + "Set 'uri' to the http/https endpoint and enable SSH via the 'ssh:' sub-block "
                            + "(ssh.enabled: true, or ssh.uri for a non-standard endpoint). This provider will not "
                            + "serve HTTP or SSH as configured.",
                    name);
        }
        URI parsedApiUri = null;
        if (providerConfig.getApiUri() != null && !providerConfig.getApiUri().isBlank()) {
            parsedApiUri = URI.create(providerConfig.getApiUri());
            String scheme = parsedApiUri.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new IllegalArgumentException("Provider '" + name
                        + "': api-uri must use http or https scheme, got: " + providerConfig.getApiUri());
            }
        }
        String apiToken = (providerConfig.getApiToken() != null
                        && !providerConfig.getApiToken().isBlank())
                ? providerConfig.getApiToken()
                : null;

        switch (resolvedType) {
            case "github" -> {
                URI githubUri = parsedUri != null ? parsedUri : GitHubProvider.DEFAULT_URI;
                return Optional.of(GitHubProvider.builder()
                        .name(name)
                        .uri(githubUri)
                        .pathSuffix(pathSuffix)
                        .apiUri(parsedApiUri)
                        .sshUri(resolveSshEndpoint(name, providerConfig, githubUri))
                        .build());
            }
            case "gitlab" -> {
                URI gitlabUri = parsedUri != null ? parsedUri : GitLabProvider.DEFAULT_URI;
                return Optional.of(GitLabProvider.builder()
                        .name(name)
                        .uri(gitlabUri)
                        .pathSuffix(pathSuffix)
                        .apiUri(parsedApiUri)
                        .apiToken(apiToken)
                        .sshUri(resolveSshEndpoint(name, providerConfig, gitlabUri))
                        .build());
            }
            case "bitbucket" -> {
                URI bitbucketUri = parsedUri != null ? parsedUri : BitbucketProvider.DEFAULT_URI;
                return Optional.of(BitbucketProvider.builder()
                        .name(name)
                        .uri(bitbucketUri)
                        .pathSuffix(pathSuffix)
                        .sshUri(resolveSshEndpoint(name, providerConfig, bitbucketUri))
                        .build());
            }
            case "codeberg" -> {
                URI codebergUri = parsedUri != null ? parsedUri : ForgejoProvider.CODEBERG;
                return Optional.of(ForgejoProvider.builder()
                        .name(name)
                        .uri(codebergUri)
                        .pathSuffix(pathSuffix)
                        .apiUri(parsedApiUri)
                        .apiToken(apiToken)
                        .sshUri(resolveSshEndpoint(name, providerConfig, codebergUri))
                        .build());
            }
            case "gitea" -> {
                URI giteaUri = parsedUri != null ? parsedUri : ForgejoProvider.GITEA;
                return Optional.of(ForgejoProvider.builder()
                        .name(name)
                        .uri(giteaUri)
                        .pathSuffix(pathSuffix)
                        .apiUri(parsedApiUri)
                        .apiToken(apiToken)
                        .sshUri(resolveSshEndpoint(name, providerConfig, giteaUri))
                        .build());
            }
            case "forgejo" -> {
                if (parsedUri == null) {
                    log.warn(
                            "Provider '{}' has type 'forgejo' but no URI — Forgejo has no canonical public host. Add 'uri'. Skipping.",
                            name);
                    return Optional.empty();
                }
                return Optional.of(ForgejoProvider.builder()
                        .name(name)
                        .uri(parsedUri)
                        .pathSuffix(pathSuffix)
                        .apiUri(parsedApiUri)
                        .apiToken(apiToken)
                        .sshUri(resolveSshEndpoint(name, providerConfig, parsedUri))
                        .build());
            }
            default -> {
                if (parsedUri != null) {
                    return Optional.of(GenericProxyProvider.builder()
                            .name(name)
                            .uri(parsedUri)
                            .pathSuffix(pathSuffix)
                            .blockedInfoRefsStatus(providerConfig.getBlockedInfoRefsStatus())
                            .sshUri(resolveSshEndpoint(name, providerConfig, parsedUri))
                            .build());
                }
                log.warn(
                        "Provider '{}' has no URI and is not a known built-in name (github/gitlab/bitbucket/forgejo/gitea). Set 'type' and 'uri' for custom providers. Skipping.",
                        name);
                return Optional.empty();
            }
        }
    }

    /**
     * Collects the per-provider {@code known_hosts} lines pinning upstream SSH host keys — each provider's
     * {@code ssh.known-hosts} inline entries plus the lines of its {@code ssh.known-hosts-path} file (#531). These are
     * merged on top of the global {@link SshConfig#getExtraKnownHosts()} / bundled defaults by
     * {@code SshGitServer.create}. known_hosts lines are keyed by host, so entries for hosts fogwall never contacts are
     * inert.
     */
    public List<String> buildUpstreamKnownHosts() {
        List<String> lines = new ArrayList<>();
        config.getProviders().forEach((name, providerConfig) -> {
            SshProviderConfig ssh = providerConfig.getSsh();
            ssh.getKnownHosts().stream()
                    .filter(l -> l != null && !l.isBlank())
                    .map(String::strip)
                    .forEach(lines::add);
            String path = ssh.getKnownHostsPath();
            if (path != null && !path.isBlank()) {
                Path file = Path.of(path);
                if (Files.isReadable(file)) {
                    try {
                        lines.addAll(Files.readAllLines(file));
                    } catch (IOException e) {
                        throw new IllegalStateException(
                                "Provider '" + name + "': failed to read ssh.known-hosts-path '" + path + "': "
                                        + e.getMessage(),
                                e);
                    }
                } else {
                    log.warn(
                            "Provider '{}': ssh.known-hosts-path '{}' is not readable — skipping those pinned host keys",
                            name,
                            path);
                }
            }
        });
        return lines;
    }

    /**
     * Resolves the SSH transport endpoint for a provider entry from its {@code ssh:} sub-block, or {@code null} when
     * the entry declares no SSH endpoint. An explicit {@code ssh.uri} wins (covering non-{@code git} SSH usernames and
     * non-standard ports, e.g. GHEC data-residency {@code ssh://{slug}@{tenant}.ghe.com}); otherwise
     * {@code ssh.enabled: true} derives {@code ssh://git@<host>} from the HTTP {@code uri} host (port 22 implied). The
     * {@code uri} must be http/https — SSH is never declared via a top-level {@code ssh://} uri.
     */
    private URI resolveSshEndpoint(String name, ProviderConfig providerConfig, URI httpUri) {
        SshProviderConfig ssh = providerConfig.getSsh();
        String explicit = ssh.getUri();
        if (explicit != null && !explicit.isBlank()) {
            URI parsed = URI.create(explicit.trim());
            if (!"ssh".equals(parsed.getScheme())) {
                throw new IllegalArgumentException("Provider '" + name
                        + "': ssh.uri must use the ssh scheme (e.g. ssh://git@host), got: " + explicit);
            }
            return parsed;
        }
        if (ssh.isEnabled()) {
            String scheme = httpUri != null ? httpUri.getScheme() : null;
            if (httpUri == null || httpUri.getHost() == null || !("http".equals(scheme) || "https".equals(scheme))) {
                throw new IllegalArgumentException("Provider '" + name
                        + "': ssh.enabled requires an http/https 'uri' to derive the SSH endpoint from, "
                        + "or an explicit 'ssh.uri'. Got uri: " + httpUri);
            }
            return URI.create("ssh://git@" + httpUri.getHost());
        }
        return null;
    }

    /** Blocked literals and patterns for proposal content ({@code proposals.block}). */
    public BlockConfig buildProposalsBlockConfig() {
        return buildBlockConfig(config.getProposals().getBlock());
    }

    private static BlockConfig buildBlockConfig(BlockSettings block) {
        List<Pattern> patterns =
                block.getPatterns().stream().map(Pattern::compile).collect(Collectors.toList());
        return BlockConfig.builder()
                .literals(new ArrayList<>(block.getLiterals()))
                .patterns(patterns)
                .build();
    }
}
