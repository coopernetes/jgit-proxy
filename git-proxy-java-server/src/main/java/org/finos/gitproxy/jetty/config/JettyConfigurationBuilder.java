package org.finos.gitproxy.jetty.config;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.approval.ApprovalGateway;
import org.finos.gitproxy.approval.AutoApprovalGateway;
import org.finos.gitproxy.approval.ServiceNowApprovalGateway;
import org.finos.gitproxy.approval.UiApprovalGateway;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.db.CompositeRepoRegistry;
import org.finos.gitproxy.db.FetchStore;
import org.finos.gitproxy.db.MongoStoreFactory;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.PushStoreFactory;
import org.finos.gitproxy.db.RepoRegistry;
import org.finos.gitproxy.db.jdbc.DataSourceFactory;
import org.finos.gitproxy.db.jdbc.JdbcFetchStore;
import org.finos.gitproxy.db.jdbc.JdbcRepoRegistry;
import org.finos.gitproxy.db.memory.InMemoryRepoRegistry;
import org.finos.gitproxy.db.model.AccessRule;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.git.LocalRepositoryCache;
import org.finos.gitproxy.jetty.GitProxyContext;
import org.finos.gitproxy.jetty.reload.ConfigHolder;
import org.finos.gitproxy.permission.JdbcRepoPermissionStore;
import org.finos.gitproxy.permission.RepoPermission;
import org.finos.gitproxy.permission.RepoPermissionService;
import org.finos.gitproxy.permission.RepoPermissionStore;
import org.finos.gitproxy.provider.*;
import org.finos.gitproxy.service.CachingTokenPushIdentityResolver;
import org.finos.gitproxy.service.JdbcScmTokenCache;
import org.finos.gitproxy.service.PushIdentityResolver;
import org.finos.gitproxy.service.TokenPushIdentityResolver;
import org.finos.gitproxy.servlet.filter.GitProxyFilter;
import org.finos.gitproxy.servlet.filter.UrlRuleFilter;
import org.finos.gitproxy.tls.SslUtil;
import org.finos.gitproxy.user.CompositeUserStore;
import org.finos.gitproxy.user.JdbcUserStore;
import org.finos.gitproxy.user.ReadOnlyUserStore;
import org.finos.gitproxy.user.ScmIdentity;
import org.finos.gitproxy.user.StaticUserStore;
import org.finos.gitproxy.user.UserEntry;
import org.finos.gitproxy.user.UserStore;

/**
 * Constructs runtime objects ({@link GitProxyProvider}, {@link CommitConfig}, {@link PushStore}, etc.) from the parsed
 * {@link GitProxyConfig}. All map-drilling and type-unsafe casting is gone — this class now just reads typed fields and
 * constructs objects.
 */
@Slf4j
public class JettyConfigurationBuilder {

    private final GitProxyConfig config;
    private List<GitProxyProvider> cachedProviders;
    private DataSource cachedDataSource;
    private MongoStoreFactory cachedMongoStoreFactory;
    private PushStore cachedPushStore;
    private FetchStore cachedFetchStore;
    private UserStore cachedUserStore;
    private JdbcScmTokenCache cachedTokenCache;
    private RepoPermissionService cachedRepoPermissionService;
    private RepoRegistry cachedRepoRegistry;
    private ConfigHolder cachedConfigHolder;

    public JettyConfigurationBuilder(GitProxyConfig config) {
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

    /** Returns whether fail-fast validation is enabled (stop after first failure). */
    public boolean isFailFast() {
        return config.getServer().isFailFast();
    }

    /** Returns the S&amp;F upstream connect timeout in seconds (0 = no timeout). */
    public int getUpstreamConnectTimeoutSeconds() {
        return config.getServer().getUpstreamConnectTimeoutSeconds();
    }

    /** Returns the transparent-proxy connect timeout in seconds (0 = no timeout). */
    public int getProxyConnectTimeoutSeconds() {
        return config.getServer().getProxyConnectTimeoutSeconds();
    }

    /**
     * Returns the live {@link ConfigHolder} pre-populated with the initial commit config. All filters and hooks that
     * support live reload receive a {@code Supplier<CommitConfig>} backed by this holder. When
     * {@link org.finos.gitproxy.jetty.reload.LiveConfigLoader} fires a reload it calls {@link ConfigHolder#update} on
     * the same instance, so all in-flight and future pushes immediately see the new config.
     */
    public ConfigHolder buildConfigHolder() {
        if (cachedConfigHolder == null) {
            cachedConfigHolder = new ConfigHolder(buildCommitConfig());
        }
        return cachedConfigHolder;
    }

    /** Returns the {@link ReloadConfig} from the parsed config file. */
    public ReloadConfig getReloadConfig() {
        return config.getReload();
    }

    /** Returns the service URL for dashboard links, defaulting to {@code http://localhost:<port>/dashboard}. */
    public String getServiceUrl() {
        String url = config.getServiceUrl();
        return (url != null && !url.isBlank()) ? url : "http://localhost:" + getServerPort() + "/dashboard";
    }

    /** Creates the list of enabled providers from configuration. Result is cached. */
    public List<GitProxyProvider> buildProviders() {
        if (cachedProviders != null) return cachedProviders;
        List<GitProxyProvider> providers = new ArrayList<>();
        Map<String, String> seenProviderIds = new LinkedHashMap<>();

        config.getProviders().forEach((name, providerConfig) -> {
            if (!providerConfig.isEnabled()) {
                log.info("Provider '{}' is disabled, skipping", name);
                return;
            }
            GitProxyProvider provider = createProvider(name, providerConfig);
            if (provider != null) {
                String existingName = seenProviderIds.put(provider.getProviderId(), provider.getName());
                if (existingName != null) {
                    throw new IllegalStateException(String.format(
                            "Provider ID conflict: '%s' and '%s' both resolve to '%s'. "
                                    + "Each provider must have a unique type/host combination.",
                            existingName, provider.getName(), provider.getProviderId()));
                }
                providers.add(provider);
                log.info(
                        "Configured provider: {} -> {} (id={})",
                        provider.getName(),
                        provider.getUri(),
                        provider.getProviderId());
            }
        });

        if (providers.isEmpty()) {
            log.warn("No providers configured. Add providers to git-proxy.yml to enable proxying.");
        }
        cachedProviders = providers;
        return cachedProviders;
    }

    /**
     * Returns the set of configured provider IDs (e.g. {@code "gitea/gitea"}, {@code "github/github.com"}). Used to
     * validate that rules, permissions, and SCM identities in the YAML config reference known providers. Crashes the
     * application if any mismatch is detected — misconfiguration must be caught at startup.
     */
    private java.util.Set<String> configuredProviderIds() {
        return buildProviders().stream()
                .map(GitProxyProvider::getProviderId)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Asserts that {@code providerId} is a configured provider ID. Throws {@link IllegalStateException} (crashing the
     * application) if not. A {@code null} or blank provider means "applies to all providers" and is always valid.
     */
    private void requireKnownProvider(String context, String providerId) {
        if (providerId == null || providerId.isBlank()) return;
        java.util.Set<String> known = configuredProviderIds();
        if (!known.contains(providerId)) {
            throw new IllegalStateException(String.format(
                    "%s references unknown provider ID '%s'. "
                            + "Provider IDs must be in type/host format (e.g. 'gitea/gitea'). "
                            + "Configured provider IDs: %s",
                    context, providerId, known));
        }
    }

    /** Creates URL rule filters for a given provider from configuration (both allow and deny rules). */
    public List<UrlRuleFilter> buildUrlRuleFilters(GitProxyProvider provider) {
        List<UrlRuleFilter> filters = new ArrayList<>();
        buildUrlRuleFiltersForAccess(filters, provider, config.getRules().getAllow(), AccessRule.Access.ALLOW);
        buildUrlRuleFiltersForAccess(filters, provider, config.getRules().getDeny(), AccessRule.Access.DENY);
        return filters;
    }

    private void buildUrlRuleFiltersForAccess(
            List<UrlRuleFilter> filters, GitProxyProvider provider, List<RuleConfig> rules, AccessRule.Access access) {
        for (RuleConfig rule : rules) {
            if (!rule.isEnabled()) continue;

            List<String> providerNames = rule.getProviders();
            // Validate every provider ID in the rule at startup
            for (String pid : providerNames) {
                requireKnownProvider(access.name() + " rule (order=" + rule.getOrder() + ")", pid);
            }
            if (!providerNames.isEmpty() && !providerNames.contains(provider.getProviderId())) {
                continue;
            }

            int order = rule.getOrder();
            String accessLabel = access.name().toLowerCase();
            Set<HttpOperation> ops = toHttpOperations(rule.getOperations());

            if (!rule.getSlugs().isEmpty()) {
                filters.add(
                        new UrlRuleFilter(order, ops, provider, rule.getSlugs(), UrlRuleFilter.Target.SLUG, access));
                log.debug("Added slug {} rule for provider {}: {}", accessLabel, provider.getName(), rule.getSlugs());
            }
            if (!rule.getOwners().isEmpty()) {
                filters.add(
                        new UrlRuleFilter(order, ops, provider, rule.getOwners(), UrlRuleFilter.Target.OWNER, access));
                log.debug("Added owner {} rule for provider {}: {}", accessLabel, provider.getName(), rule.getOwners());
            }
            if (!rule.getNames().isEmpty()) {
                filters.add(
                        new UrlRuleFilter(order, ops, provider, rule.getNames(), UrlRuleFilter.Target.NAME, access));
                log.debug("Added name {} rule for provider {}: {}", accessLabel, provider.getName(), rule.getNames());
            }
        }
    }

    /**
     * Builds a {@link CommitConfig} from the {@code commit:} YAML section. Pattern strings are compiled here; absent or
     * blank strings produce permissive defaults (no restriction).
     */
    public CommitConfig buildCommitConfig() {
        CommitSettings cs = config.getCommit();
        CommitSettings.EmailSettings email = cs.getAuthor().getEmail();

        String domainAllow = email.getDomain().getAllow();
        CommitConfig.DomainConfig domainConfig = (domainAllow != null && !domainAllow.isBlank())
                ? CommitConfig.DomainConfig.builder()
                        .allow(Pattern.compile(domainAllow))
                        .build()
                : CommitConfig.DomainConfig.builder().build();

        String localBlock = email.getLocal().getBlock();
        CommitConfig.LocalConfig localConfig = (localBlock != null && !localBlock.isBlank())
                ? CommitConfig.LocalConfig.builder()
                        .block(Pattern.compile(localBlock))
                        .build()
                : CommitConfig.LocalConfig.builder().build();

        CommitConfig.MessageConfig messageConfig = CommitConfig.MessageConfig.builder()
                .block(buildBlockConfig(cs.getMessage().getBlock()))
                .build();

        CommitConfig.DiffConfig diffConfig = CommitConfig.DiffConfig.builder()
                .block(buildBlockConfig(cs.getDiff().getBlock()))
                .build();

        CommitSettings.SecretScanningSettings ss = cs.getSecretScanning();
        CommitConfig.SecretScanningConfig secretScanning = CommitConfig.SecretScanningConfig.builder()
                .enabled(ss.isEnabled())
                .autoInstall(ss.isAutoInstall())
                .installDir(ss.getInstallDir())
                .version(ss.getVersion())
                .scannerPath(ss.getScannerPath())
                .configFile(ss.getConfigFile())
                .timeoutSeconds(ss.getTimeoutSeconds())
                .build();

        CommitConfig.IdentityVerificationMode identityVerificationMode =
                CommitConfig.IdentityVerificationMode.fromString(cs.getIdentityVerification());

        CommitConfig commitConfig = CommitConfig.builder()
                .identityVerification(identityVerificationMode)
                .author(CommitConfig.AuthorConfig.builder()
                        .email(CommitConfig.EmailConfig.builder()
                                .domain(domainConfig)
                                .local(localConfig)
                                .build())
                        .build())
                .message(messageConfig)
                .diff(diffConfig)
                .secretScanning(secretScanning)
                .build();

        log.info(
                "Loaded commit config: domain.allow={}, local.block={}, message.literals={}, message.patterns={},"
                        + " diff.literals={}, diff.patterns={}, secretScanning.enabled={}",
                domainAllow != null ? domainAllow : "(none)",
                localBlock != null ? localBlock : "(none)",
                commitConfig.getMessage().getBlock().getLiterals().size(),
                commitConfig.getMessage().getBlock().getPatterns().size(),
                commitConfig.getDiff().getBlock().getLiterals().size(),
                commitConfig.getDiff().getBlock().getPatterns().size(),
                secretScanning.isEnabled());

        return commitConfig;
    }

    /**
     * Builds the complete {@link GitProxyContext} using the config-derived {@link ApprovalGateway} (based on
     * {@code server.approval-mode}).
     */
    public GitProxyContext buildProxyContext() throws IOException {
        PushStore ps = buildPushStore();
        return buildProxyContextWith(buildApprovalGateway(ps));
    }

    /**
     * Builds the complete {@link GitProxyContext} with a caller-supplied {@link ApprovalGateway}. Used by the dashboard
     * application, which always forces {@link org.finos.gitproxy.approval.UiApprovalGateway} regardless of config.
     */
    public GitProxyContext buildProxyContext(ApprovalGateway approvalGateway) throws IOException {
        return buildProxyContextWith(approvalGateway);
    }

    private GitProxyContext buildProxyContextWith(ApprovalGateway approvalGateway) throws IOException {
        PushStore ps = buildPushStore();
        FetchStore fs = buildFetchStore();
        UserStore us = buildUserStore();
        RepoRegistry rr = buildRepoRegistry();
        var storeForwardCache = new LocalRepositoryCache(Files.createTempDirectory("git-proxy-java-sf-"), 0, true);
        log.info("Initialized store-and-forward LocalRepositoryCache (full clone)");
        var proxyCache = new LocalRepositoryCache();
        log.info("Initialized proxy LocalRepositoryCache (shallow clone)");
        return new GitProxyContext(
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
                isFailFast(),
                getUpstreamConnectTimeoutSeconds(),
                getProxyConnectTimeoutSeconds(),
                storeForwardCache,
                proxyCache,
                buildUpstreamTls());
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

    /** Builds a {@link RepoPermissionStore} backed by the configured database. */
    public RepoPermissionStore buildRepoPermissionStore() {
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
        RepoPermissionStore store = buildRepoPermissionStore();
        store.initialize();
        cachedRepoPermissionService = new RepoPermissionService(store);

        List<RepoPermission> configPerms = config.getPermissions().stream()
                .map(p -> {
                    requireKnownProvider("Permission for user '" + p.getUsername() + "'", p.getProvider());
                    return RepoPermission.builder()
                            .username(p.getUsername())
                            .provider(p.getProvider())
                            .path(p.getPath())
                            .pathType(RepoPermission.PathType.valueOf(
                                    p.getPathType().toUpperCase()))
                            .operations(RepoPermission.Operations.valueOf(
                                    p.getOperations().toUpperCase()))
                            .source(RepoPermission.Source.CONFIG)
                            .build();
                })
                .toList();
        cachedRepoPermissionService.seedFromConfig(configPerms);

        log.info("RepoPermissionService initialized with {} config permission(s)", configPerms.size());
        return cachedRepoPermissionService;
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
            case "servicenow" -> {
                log.info("Approval mode: servicenow");
                String snUrl = System.getenv().getOrDefault("GITPROXY_SERVICENOW_URL", "");
                String snCreds = System.getenv().getOrDefault("GITPROXY_SERVICENOW_CREDENTIALS", "");
                yield new ServiceNowApprovalGateway(snUrl, snCreds);
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
            case "h2-mem", "h2-file", "postgres" -> PushStoreFactory.fromDataSource(requireJdbcDataSource());
            case "mongo" -> requireMongoStoreFactory().pushStore();
            default ->
                throw new IllegalArgumentException(
                        "Unknown database type: " + db.getType() + ". Supported: h2-mem, h2-file, postgres, mongo");
        };
        return cachedPushStore;
    }

    /**
     * Builds a {@link RepoRegistry}, seeding it with rules derived from the YAML allow and deny rules config. JDBC
     * backends share the same {@link DataSource} as the push store.
     */
    public RepoRegistry buildRepoRegistry() {
        if (cachedRepoRegistry != null) return cachedRepoRegistry;
        // CONFIG rules live only in memory — never written to DB, no stale duplicates on restart.
        InMemoryRepoRegistry configRegistry = new InMemoryRepoRegistry();
        seedRulesIntoRegistry(configRegistry, config.getRules().getAllow(), AccessRule.Access.ALLOW);
        seedRulesIntoRegistry(configRegistry, config.getRules().getDeny(), AccessRule.Access.DENY);

        String type = config.getDatabase().getType();
        RepoRegistry dbRegistry;
        if ("mongo".equals(type)) {
            dbRegistry = requireMongoStoreFactory().repoRegistry();
        } else {
            dbRegistry = new JdbcRepoRegistry(requireJdbcDataSource());
        }

        cachedRepoRegistry = new CompositeRepoRegistry(configRegistry, dbRegistry);
        cachedRepoRegistry.initialize();
        log.info(
                "RepoRegistry initialized ({} config rules, {} db rules)",
                configRegistry.findAll().size(),
                dbRegistry.findAll().size());
        return cachedRepoRegistry;
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

    private void seedRulesIntoRegistry(
            InMemoryRepoRegistry registry, List<RuleConfig> rules, AccessRule.Access access) {
        for (RuleConfig rule : rules) {
            if (!rule.isEnabled()) continue;
            AccessRule.Operations ops = toOperations(rule.getOperations());
            List<String> providers =
                    rule.getProviders().isEmpty() ? java.util.Collections.singletonList(null) : rule.getProviders();
            for (String provider : providers) {
                requireKnownProvider(access.name() + " rule (order=" + rule.getOrder() + ")", provider);
                for (String rawSlug : rule.getSlugs()) {
                    String slug = rawSlug.startsWith("/") ? rawSlug : "/" + rawSlug;
                    registry.save(AccessRule.builder()
                            .provider(provider)
                            .slug(slug)
                            .access(access)
                            .operations(ops)
                            .source(AccessRule.Source.CONFIG)
                            .ruleOrder(rule.getOrder())
                            .build());
                }
                for (String owner : rule.getOwners()) {
                    registry.save(AccessRule.builder()
                            .provider(provider)
                            .owner(owner)
                            .access(access)
                            .operations(ops)
                            .source(AccessRule.Source.CONFIG)
                            .ruleOrder(rule.getOrder())
                            .build());
                }
                for (String name : rule.getNames()) {
                    registry.save(AccessRule.builder()
                            .provider(provider)
                            .name(name)
                            .access(access)
                            .operations(ops)
                            .source(AccessRule.Source.CONFIG)
                            .ruleOrder(rule.getOrder())
                            .build());
                }
            }
        }
    }

    private static AccessRule.Operations toOperations(List<String> ops) {
        if (ops == null || ops.isEmpty() || ops.size() > 1) return AccessRule.Operations.BOTH;
        return switch (ops.get(0).toUpperCase()) {
            case "FETCH" -> AccessRule.Operations.FETCH;
            case "PUSH" -> AccessRule.Operations.PUSH;
            default -> AccessRule.Operations.BOTH;
        };
    }

    private static Set<HttpOperation> toHttpOperations(List<String> ops) {
        if (ops == null || ops.isEmpty() || ops.size() > 1) return GitProxyFilter.DEFAULT_OPERATIONS;
        return switch (ops.get(0).toUpperCase()) {
            case "FETCH" -> Set.of(HttpOperation.FETCH);
            case "PUSH" -> Set.of(HttpOperation.PUSH);
            default -> GitProxyFilter.DEFAULT_OPERATIONS;
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
                                // "proxy" is a synthetic provider for push-username lookup; all others must be known
                                if (!"proxy".equals(s.getProvider())) {
                                    requireKnownProvider(
                                            "User '" + uc.getUsername() + "' scm-identity", s.getProvider());
                                }
                                return ScmIdentity.builder()
                                        .provider(s.getProvider())
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
                    return UserEntry.builder()
                            .username(uc.getUsername())
                            .passwordHash(uc.getPasswordHash())
                            .emails(uc.getEmails())
                            .scmIdentities(scmIdentities)
                            .roles(roles)
                            .build();
                })
                .toList();

        String type = config.getDatabase().getType();
        if ("mongo".equals(type)) {
            var mongoStore = requireMongoStoreFactory().userStore();
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
     * {@code GITPROXY_SCM_CACHE_MAX_AGE_DAYS} environment variable.
     */
    public PushIdentityResolver buildPushIdentityResolver(ReadOnlyUserStore userStore) {
        if (config.getUsers().isEmpty()) return null;

        PushIdentityResolver tokenResolver = new TokenPushIdentityResolver(userStore);

        String dbType = config.getDatabase().getType();
        if (!"mongo".equals(dbType)) {
            JdbcScmTokenCache tokenCache = cachedTokenCache != null ? cachedTokenCache : buildTokenCache();
            tokenResolver = new CachingTokenPushIdentityResolver(tokenResolver, tokenCache, userStore);
        }

        return tokenResolver;
    }

    private JdbcScmTokenCache buildTokenCache() {
        long maxAgeDays = Optional.ofNullable(System.getenv("GITPROXY_SCM_CACHE_MAX_AGE_DAYS"))
                .map(Long::parseLong)
                .orElse(7L);
        log.info("SCM token identity cache enabled (max age {} days)", maxAgeDays);
        return new JdbcScmTokenCache(requireJdbcDataSource(), Duration.ofDays(maxAgeDays));
    }

    private MongoStoreFactory requireMongoStoreFactory() {
        if (cachedMongoStoreFactory == null) {
            DatabaseConfig db = config.getDatabase();
            cachedMongoStoreFactory = new MongoStoreFactory(db.getUrl(), mongoDbName(db));
        }
        return cachedMongoStoreFactory;
    }

    private DataSource requireJdbcDataSource() {
        if (cachedDataSource == null) {
            DatabaseConfig db = config.getDatabase();
            cachedDataSource = switch (db.getType()) {
                case "h2-mem" -> DataSourceFactory.h2InMemory(db.getName());
                case "h2-file" ->
                    DataSourceFactory.h2File(db.getPath().isBlank() ? "./.data/" + db.getName() : db.getPath());
                case "postgres" -> {
                    if (!db.getUrl().isBlank()) {
                        log.info("Postgres: using connection URL (individual host/port/name fields ignored)");
                        yield DataSourceFactory.fromUrl(db.getUrl(), db.getUsername(), db.getPassword());
                    }
                    yield DataSourceFactory.postgres(
                            db.getHost(), db.getPort(), db.getName(), db.getUsername(), db.getPassword());
                }
                default -> throw new IllegalStateException("No JDBC DataSource for db type: " + db.getType());
            };
        }
        return cachedDataSource;
    }

    /**
     * Resolves the MongoDB database name. If {@code name} is non-blank, uses it directly. Otherwise attempts to extract
     * the database name from the URI path (e.g. {@code mongodb://host/mydb} → {@code mydb}), falling back to
     * {@code "gitproxy"}.
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
        return "gitproxy";
    }

    private GitProxyProvider createProvider(String name, ProviderConfig providerConfig) {
        String explicitType = providerConfig.getType();
        // Use explicit type if set; otherwise accept only exact built-in names, not fuzzy name inference.
        String resolvedType = (explicitType != null && !explicitType.isBlank())
                ? explicitType.toLowerCase().trim()
                : name.toLowerCase();

        String uri = providerConfig.getUri();
        String path = providerConfig.getServletPath();
        URI parsedUri = (uri != null && !uri.isBlank()) ? URI.create(uri) : null;

        switch (resolvedType) {
            case "github" -> {
                return GitHubProvider.builder()
                        .name(name)
                        .uri(parsedUri)
                        .basePath(path)
                        .build();
            }
            case "gitlab" -> {
                return GitLabProvider.builder()
                        .name(name)
                        .uri(parsedUri)
                        .basePath(path)
                        .build();
            }
            case "bitbucket" -> {
                return BitbucketProvider.builder()
                        .name(name)
                        .uri(parsedUri)
                        .basePath(path)
                        .build();
            }
            case "codeberg", "gitea" -> {
                URI defaultUri = ForgejoProvider.WELL_KNOWN.get(resolvedType);
                return ForgejoProvider.builder()
                        .name(name)
                        .uri(parsedUri != null ? parsedUri : defaultUri)
                        .basePath(path)
                        .build();
            }
            case "forgejo" -> {
                if (parsedUri == null) {
                    log.warn(
                            "Provider '{}' has type 'forgejo' but no URI — Forgejo has no canonical public host. Add 'uri'. Skipping.",
                            name);
                    return null;
                }
                return ForgejoProvider.builder()
                        .name(name)
                        .uri(parsedUri)
                        .basePath(path)
                        .build();
            }
            default -> {
                if (parsedUri != null) {
                    return GenericProxyProvider.builder()
                            .name(name)
                            .uri(parsedUri)
                            .basePath(path)
                            .blockedInfoRefsStatus(providerConfig.getBlockedInfoRefsStatus())
                            .build();
                }
                log.warn(
                        "Provider '{}' has no URI and is not a known built-in name (github/gitlab/bitbucket/codeberg/forgejo/gitea). Set 'type' and 'uri' for custom providers. Skipping.",
                        name);
                return null;
            }
        }
    }

    private static CommitConfig.BlockConfig buildBlockConfig(CommitSettings.BlockSettings block) {
        List<Pattern> patterns =
                block.getPatterns().stream().map(Pattern::compile).collect(Collectors.toList());
        return CommitConfig.BlockConfig.builder()
                .literals(new ArrayList<>(block.getLiterals()))
                .patterns(patterns)
                .build();
    }
}
