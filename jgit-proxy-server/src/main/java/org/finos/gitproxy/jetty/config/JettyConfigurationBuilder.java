package org.finos.gitproxy.jetty.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.approval.ApprovalGateway;
import org.finos.gitproxy.approval.AutoApprovalGateway;
import org.finos.gitproxy.approval.ServiceNowApprovalGateway;
import org.finos.gitproxy.approval.UiApprovalGateway;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.PushStoreFactory;
import org.finos.gitproxy.db.jdbc.DataSourceFactory;
import org.finos.gitproxy.provider.*;
import org.finos.gitproxy.service.ConfigPushIdentityResolver;
import org.finos.gitproxy.service.DummyUserAuthorizationService;
import org.finos.gitproxy.service.LinkedIdentityAuthorizationService;
import org.finos.gitproxy.service.PushIdentityResolver;
import org.finos.gitproxy.service.UserAuthorizationService;
import org.finos.gitproxy.servlet.filter.RepositoryUrlFilter;
import org.finos.gitproxy.servlet.filter.WhitelistByUrlFilter;
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
                filters.add(new WhitelistByUrlFilter(order, provider, wl.getSlugs(), RepositoryUrlFilter.Target.SLUG));
                log.debug("Added slug whitelist for provider {}: {}", provider.getName(), wl.getSlugs());
            }
            if (!wl.getOwners().isEmpty()) {
                filters.add(
                        new WhitelistByUrlFilter(order, provider, wl.getOwners(), RepositoryUrlFilter.Target.OWNER));
                log.debug("Added owner whitelist for provider {}: {}", provider.getName(), wl.getOwners());
            }
            if (!wl.getNames().isEmpty()) {
                filters.add(new WhitelistByUrlFilter(order, provider, wl.getNames(), RepositoryUrlFilter.Target.NAME));
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

        CommitConfig commitConfig = CommitConfig.builder()
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
        DatabaseConfig db = config.getDatabase();
        log.info("Initializing push store: type={}", db.getType());

        return switch (db.getType()) {
            case "memory" -> PushStoreFactory.inMemory();
            case "h2-mem", "h2-file", "sqlite", "postgres" -> PushStoreFactory.fromDataSource(requireJdbcDataSource());
            case "mongo" -> PushStoreFactory.mongo(db.getUrl(), db.getName());
            default ->
                throw new IllegalArgumentException("Unknown database type: " + db.getType()
                        + ". Supported: memory, h2-mem, h2-file, sqlite, postgres, mongo");
        };
    }

    /** Builds a {@link UserStore} from config. JDBC backends share the same {@link DataSource} as the push store. */
    public UserStore buildUserStore() {
        List<UserEntry> staticUsers = config.getUsers().stream()
                .map(uc -> UserEntry.builder()
                        .username(uc.getUsername())
                        .passwordHash(uc.getPasswordHash())
                        .emails(uc.getEmails())
                        .pushUsernames(uc.getPushUsernames())
                        .scmIdentities(uc.getScmIdentities().stream()
                                .map(s -> ScmIdentity.builder()
                                        .provider(s.getProvider())
                                        .username(s.getUsername())
                                        .build())
                                .toList())
                        .build())
                .toList();

        String type = config.getDatabase().getType();
        if ("memory".equals(type) || "mongo".equals(type)) {
            log.info("Using in-memory user store ({} users)", staticUsers.size());
            return new StaticUserStore(staticUsers);
        }
        JdbcUserStore store = new JdbcUserStore(requireJdbcDataSource());
        store.upsertAll(staticUsers);
        log.info("Using JDBC user store ({} users seeded)", staticUsers.size());
        return store;
    }

    /**
     * Builds the {@link PushIdentityResolver}. Uses {@link ConfigPushIdentityResolver} when users are configured,
     * otherwise returns null (no identity checks).
     */
    public PushIdentityResolver buildPushIdentityResolver(UserStore userStore) {
        if (config.getUsers().isEmpty()) return null;
        return new ConfigPushIdentityResolver(userStore);
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
                case "sqlite" ->
                    DataSourceFactory.sqlite(db.getPath().isBlank() ? "./.data/" + db.getName() + ".db" : db.getPath());
                case "postgres" ->
                    DataSourceFactory.postgres(
                            db.getHost(), db.getPort(), db.getName(), db.getUsername(), db.getPassword());
                default -> throw new IllegalStateException("No JDBC DataSource for db type: " + db.getType());
            };
        }
        return cachedDataSource;
    }

    private GitProxyProvider createProvider(String name, ProviderConfig providerConfig) {
        String normalized = name.toLowerCase().replace("-", "").replace("_", "");
        String uri = providerConfig.getUri();
        String path = providerConfig.getServletPath();

        if (normalized.contains("github")) {
            return (uri != null && !uri.isBlank())
                    ? GenericProxyProvider.builder()
                            .name(name)
                            .uri(URI.create(uri))
                            .basePath(path)
                            .build()
                    : new GitHubProvider(path);
        } else if (normalized.contains("gitlab")) {
            return (uri != null && !uri.isBlank())
                    ? GenericProxyProvider.builder()
                            .name(name)
                            .uri(URI.create(uri))
                            .basePath(path)
                            .build()
                    : new GitLabProvider(path);
        } else if (normalized.contains("bitbucket")) {
            return (uri != null && !uri.isBlank())
                    ? GenericProxyProvider.builder()
                            .name(name)
                            .uri(URI.create(uri))
                            .basePath(path)
                            .build()
                    : new BitbucketProvider(path);
        } else if (uri != null && !uri.isBlank()) {
            return GenericProxyProvider.builder()
                    .name(name)
                    .uri(URI.create(uri))
                    .basePath(path)
                    .build();
        } else {
            log.warn("Provider '{}' has no URI and is not a known built-in type. Skipping.", name);
            return null;
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
