package org.finos.gitproxy.jetty.config;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.PushStoreFactory;
import org.finos.gitproxy.db.RepoRegistry;
import org.finos.gitproxy.db.jdbc.DataSourceFactory;
import org.finos.gitproxy.db.jdbc.JdbcFetchStore;
import org.finos.gitproxy.db.jdbc.JdbcRepoRegistry;
import org.finos.gitproxy.db.memory.InMemoryFetchStore;
import org.finos.gitproxy.db.memory.InMemoryRepoRegistry;
import org.finos.gitproxy.db.model.AccessRule;
import org.finos.gitproxy.git.LocalRepositoryCache;
import org.finos.gitproxy.jetty.GitProxyContext;
import org.finos.gitproxy.provider.*;
import org.finos.gitproxy.service.CachingTokenPushIdentityResolver;
import org.finos.gitproxy.service.DummyUserAuthorizationService;
import org.finos.gitproxy.service.JdbcScmTokenCache;
import org.finos.gitproxy.service.LinkedIdentityAuthorizationService;
import org.finos.gitproxy.service.PushIdentityResolver;
import org.finos.gitproxy.service.TokenPushIdentityResolver;
import org.finos.gitproxy.service.UserAuthorizationService;
import org.finos.gitproxy.servlet.filter.AuthorizedByUrlFilter;
import org.finos.gitproxy.servlet.filter.WhitelistByUrlFilter;
import org.finos.gitproxy.user.CompositeUserStore;
import org.finos.gitproxy.user.JdbcUserStore;
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
    private DataSource cachedDataSource;
    private PushStore cachedPushStore;
    private FetchStore cachedFetchStore;
    private UserStore cachedUserStore;
    private JdbcScmTokenCache cachedTokenCache;

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

    /** Returns the service URL for dashboard links, defaulting to {@code http://localhost:<port>}. */
    public String getServiceUrl() {
        String url = config.getServiceUrl();
        return (url != null && !url.isBlank()) ? url : "http://localhost:" + getServerPort();
    }

    /** Creates the list of enabled providers from configuration. */
    public List<GitProxyProvider> buildProviders() {
        List<GitProxyProvider> providers = new ArrayList<>();

        config.getProviders().forEach((name, providerConfig) -> {
            if (!providerConfig.isEnabled()) {
                log.info("Provider '{}' is disabled, skipping", name);
                return;
            }
            GitProxyProvider provider = createProvider(name, providerConfig);
            if (provider != null) {
                providers.add(provider);
                log.info("Configured provider: {} -> {}", provider.getName(), provider.getUri());
            }
        });

        if (providers.isEmpty()) {
            log.warn("No providers configured. Add providers to git-proxy.yml to enable proxying.");
        }
        return providers;
    }

    /** Creates whitelist filters for a given provider from configuration. */
    public List<WhitelistByUrlFilter> buildWhitelistFilters(GitProxyProvider provider) {
        List<WhitelistByUrlFilter> filters = new ArrayList<>();

        for (WhitelistConfig wl : config.getFilters().getWhitelists()) {
            if (!wl.isEnabled()) continue;

            // Scope check: skip if this whitelist is scoped to specific providers that exclude this one
            List<String> providerNames = wl.getProviders();
            if (!providerNames.isEmpty()
                    && !providerNames.contains(provider.getName().toLowerCase())) {
                continue;
            }

            int order = wl.getOrder();

            if (!wl.getSlugs().isEmpty()) {
                filters.add(
                        new WhitelistByUrlFilter(order, provider, wl.getSlugs(), AuthorizedByUrlFilter.Target.SLUG));
                log.debug("Added slug whitelist for provider {}: {}", provider.getName(), wl.getSlugs());
            }
            if (!wl.getOwners().isEmpty()) {
                filters.add(
                        new WhitelistByUrlFilter(order, provider, wl.getOwners(), AuthorizedByUrlFilter.Target.OWNER));
                log.debug("Added owner whitelist for provider {}: {}", provider.getName(), wl.getOwners());
            }
            if (!wl.getNames().isEmpty()) {
                filters.add(
                        new WhitelistByUrlFilter(order, provider, wl.getNames(), AuthorizedByUrlFilter.Target.NAME));
                log.debug("Added name whitelist for provider {}: {}", provider.getName(), wl.getNames());
            }
        }

        return filters;
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
        var storeForwardCache = new LocalRepositoryCache(Files.createTempDirectory("jgit-proxy-sf-"), 0, true);
        log.info("Initialized store-and-forward LocalRepositoryCache (full clone)");
        var proxyCache = new LocalRepositoryCache();
        log.info("Initialized proxy LocalRepositoryCache (shallow clone)");
        return new GitProxyContext(
                ps,
                fs,
                us,
                buildUserAuthService(us),
                buildPushIdentityResolver(us),
                approvalGateway,
                buildCommitConfig(),
                getServiceUrl(),
                getHeartbeatIntervalSeconds(),
                isFailFast(),
                getUpstreamConnectTimeoutSeconds(),
                getProxyConnectTimeoutSeconds(),
                storeForwardCache,
                proxyCache);
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
            case "memory" -> PushStoreFactory.inMemory();
            case "h2-mem", "h2-file", "postgres" -> PushStoreFactory.fromDataSource(requireJdbcDataSource());
            case "mongo" -> PushStoreFactory.mongo(db.getUrl(), db.getName());
            default ->
                throw new IllegalArgumentException("Unknown database type: " + db.getType()
                        + ". Supported: memory, h2-mem, h2-file, postgres, mongo");
        };
        return cachedPushStore;
    }

    /**
     * Builds a {@link RepoRegistry}, seeding it with rules derived from the YAML whitelist config. JDBC backends share
     * the same {@link DataSource} as the push store.
     */
    public RepoRegistry buildRepoRegistry() {
        // CONFIG rules live only in memory — never written to DB, no stale duplicates on restart.
        InMemoryRepoRegistry configRegistry = new InMemoryRepoRegistry();
        for (WhitelistConfig wl : config.getFilters().getWhitelists()) {
            if (!wl.isEnabled()) continue;
            AccessRule.Operations ops = toOperations(wl.getOperations());
            // A whitelist entry with no providers means "all providers" — one rule with provider=null.
            // An entry scoped to N providers produces N rules, one per provider name.
            List<String> providers =
                    wl.getProviders().isEmpty() ? java.util.Collections.singletonList(null) : wl.getProviders();
            for (String provider : providers) {
                for (String slug : wl.getSlugs()) {
                    configRegistry.save(AccessRule.builder()
                            .provider(provider)
                            .slug(slug)
                            .access(AccessRule.Access.ALLOW)
                            .operations(ops)
                            .source(AccessRule.Source.CONFIG)
                            .ruleOrder(wl.getOrder())
                            .build());
                }
                for (String owner : wl.getOwners()) {
                    configRegistry.save(AccessRule.builder()
                            .provider(provider)
                            .owner(owner)
                            .access(AccessRule.Access.ALLOW)
                            .operations(ops)
                            .source(AccessRule.Source.CONFIG)
                            .ruleOrder(wl.getOrder())
                            .build());
                }
                for (String name : wl.getNames()) {
                    configRegistry.save(AccessRule.builder()
                            .provider(provider)
                            .name(name)
                            .access(AccessRule.Access.ALLOW)
                            .operations(ops)
                            .source(AccessRule.Source.CONFIG)
                            .ruleOrder(wl.getOrder())
                            .build());
                }
            }
        }

        String type = config.getDatabase().getType();
        RepoRegistry dbRegistry;
        if ("memory".equals(type) || "mongo".equals(type)) {
            dbRegistry = new InMemoryRepoRegistry();
        } else {
            dbRegistry = new JdbcRepoRegistry(requireJdbcDataSource());
        }

        RepoRegistry registry = new CompositeRepoRegistry(configRegistry, dbRegistry);
        registry.initialize();
        log.info(
                "RepoRegistry initialized ({} config rules, {} db rules)",
                configRegistry.findAll().size(),
                dbRegistry.findAll().size());
        return registry;
    }

    /** Builds a {@link FetchStore}. JDBC backends share the same {@link DataSource} as the push store. */
    public FetchStore buildFetchStore() {
        if (cachedFetchStore != null) return cachedFetchStore;
        String type = config.getDatabase().getType();
        FetchStore store;
        if ("memory".equals(type) || "mongo".equals(type)) {
            store = new InMemoryFetchStore();
        } else {
            store = new JdbcFetchStore(requireJdbcDataSource());
        }
        store.initialize();
        cachedFetchStore = store;
        return cachedFetchStore;
    }

    private static AccessRule.Operations toOperations(List<String> ops) {
        if (ops == null || ops.isEmpty() || ops.size() > 1) return AccessRule.Operations.ALL;
        return switch (ops.get(0).toUpperCase()) {
            case "FETCH" -> AccessRule.Operations.FETCH;
            case "PUSH" -> AccessRule.Operations.PUSH;
            default -> AccessRule.Operations.ALL;
        };
    }

    /** Builds a {@link UserStore} from config. JDBC backends share the same {@link DataSource} as the push store. */
    public UserStore buildUserStore() {
        if (cachedUserStore != null) return cachedUserStore;
        List<UserEntry> staticUsers = config.getUsers().stream()
                .map(uc -> {
                    List<ScmIdentity> scmIdentities = new ArrayList<>();
                    uc.getScmIdentities().stream()
                            .map(s -> ScmIdentity.builder()
                                    .provider(s.getProvider())
                                    .username(s.getUsername())
                                    .build())
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
        if ("memory".equals(type) || "mongo".equals(type)) {
            log.info("Using in-memory user store ({} users)", staticUsers.size());
            cachedUserStore = new StaticUserStore(staticUsers);
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
    public PushIdentityResolver buildPushIdentityResolver(UserStore userStore) {
        if (config.getUsers().isEmpty()) return null;

        PushIdentityResolver tokenResolver = new TokenPushIdentityResolver(userStore);

        String dbType = config.getDatabase().getType();
        if (!"memory".equals(dbType) && !"mongo".equals(dbType)) {
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

    /**
     * Builds the {@link UserAuthorizationService}. Uses {@link LinkedIdentityAuthorizationService} when users are
     * configured, otherwise falls back to {@link DummyUserAuthorizationService} (open/permissive mode).
     */
    public UserAuthorizationService buildUserAuthService(UserStore userStore) {
        if (config.getUsers().isEmpty()) {
            log.info("No users configured — open authorization mode (all pushes permitted)");
            return new DummyUserAuthorizationService();
        }
        log.info("User authorization enabled ({} users)", config.getUsers().size());
        return new LinkedIdentityAuthorizationService(userStore);
    }

    private DataSource requireJdbcDataSource() {
        if (cachedDataSource == null) {
            DatabaseConfig db = config.getDatabase();
            cachedDataSource = switch (db.getType()) {
                case "h2-mem" -> DataSourceFactory.h2InMemory(db.getName());
                case "h2-file" ->
                    DataSourceFactory.h2File(db.getPath().isBlank() ? "./.data/" + db.getName() : db.getPath());
                case "postgres" ->
                    DataSourceFactory.postgres(
                            db.getHost(), db.getPort(), db.getName(), db.getUsername(), db.getPassword());
                default -> throw new IllegalStateException("No JDBC DataSource for db type: " + db.getType());
            };
        }
        return cachedDataSource;
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
                return parsedUri != null ? new GitHubProvider(parsedUri, path, null) : new GitHubProvider(path);
            }
            case "gitlab" -> {
                return parsedUri != null ? new GitLabProvider(parsedUri, path, null) : new GitLabProvider(path);
            }
            case "bitbucket" -> {
                return parsedUri != null ? new BitbucketProvider(parsedUri, path, null) : new BitbucketProvider(path);
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
