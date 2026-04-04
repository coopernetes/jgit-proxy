package org.finos.gitproxy.jetty.config;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.approval.ApprovalGateway;
import org.finos.gitproxy.approval.AutoApprovalGateway;
import org.finos.gitproxy.approval.ServiceNowApprovalGateway;
import org.finos.gitproxy.approval.UiApprovalGateway;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.PushStoreFactory;
import org.finos.gitproxy.provider.*;
import org.finos.gitproxy.servlet.filter.RepositoryUrlFilter;
import org.finos.gitproxy.servlet.filter.WhitelistByUrlFilter;

/**
 * Builds providers and whitelist filters from the parsed YAML configuration.
 *
 * <p>Supported built-in providers: github, gitlab, bitbucket. Custom providers can be defined with a URI and optional
 * servlet-path.
 *
 * <p>Whitelist filters support matching by slugs, owners, or repository names and can be scoped to specific providers.
 */
@Slf4j
public class JettyConfigurationBuilder {

    private static final Set<String> KNOWN_PROVIDERS = Set.of("github", "gitlab", "bitbucket");

    private final JettyConfigurationLoader configLoader;

    public JettyConfigurationBuilder(JettyConfigurationLoader configLoader) {
        this.configLoader = configLoader;
    }

    /** Creates the list of providers from configuration. */
    public List<GitProxyProvider> buildProviders() {
        List<GitProxyProvider> providers = new ArrayList<>();
        Map<String, Map<String, Object>> providerConfigs = configLoader.getProviders();

        for (Map.Entry<String, Map<String, Object>> entry : providerConfigs.entrySet()) {
            String name = entry.getKey();
            Map<String, Object> providerConfig = entry.getValue();

            boolean enabled = getBoolean(providerConfig, "enabled", true);
            if (!enabled) {
                log.info("Provider '{}' is disabled, skipping", name);
                continue;
            }

            String servletPath = getString(providerConfig, "servlet-path", "");
            String uriStr = getString(providerConfig, "uri", null);

            GitProxyProvider provider = createProvider(name, servletPath, uriStr);
            if (provider != null) {
                providers.add(provider);
                log.info("Configured provider: {} -> {}", provider.getName(), provider.getUri());
            }
        }

        if (providers.isEmpty()) {
            log.warn("No providers configured. Add providers to git-proxy.yml to enable proxying.");
        }

        return providers;
    }

    /** Creates whitelist filters for a given provider from configuration. */
    public List<WhitelistByUrlFilter> buildWhitelistFilters(GitProxyProvider provider) {
        List<WhitelistByUrlFilter> filters = new ArrayList<>();
        List<Map<String, Object>> whitelistConfigs = configLoader.getWhitelistFilters();

        for (Map<String, Object> whitelistConfig : whitelistConfigs) {
            boolean enabled = getBoolean(whitelistConfig, "enabled", true);
            if (!enabled) {
                continue;
            }

            // Check if this whitelist applies to this provider
            List<String> providerNames = getStringList(whitelistConfig, "providers");
            if (!providerNames.isEmpty()
                    && !providerNames.contains(provider.getName().toLowerCase())) {
                continue;
            }

            int order = getInt(whitelistConfig, "order", 110);

            // Build whitelist filters for each target type
            List<String> slugs = getStringList(whitelistConfig, "slugs");
            if (!slugs.isEmpty()) {
                filters.add(new WhitelistByUrlFilter(order, provider, slugs, RepositoryUrlFilter.Target.SLUG));
                log.debug("Added slug whitelist for provider {}: {}", provider.getName(), slugs);
            }

            List<String> owners = getStringList(whitelistConfig, "owners");
            if (!owners.isEmpty()) {
                filters.add(new WhitelistByUrlFilter(order, provider, owners, RepositoryUrlFilter.Target.OWNER));
                log.debug("Added owner whitelist for provider {}: {}", provider.getName(), owners);
            }

            List<String> names = getStringList(whitelistConfig, "names");
            if (!names.isEmpty()) {
                filters.add(new WhitelistByUrlFilter(order, provider, names, RepositoryUrlFilter.Target.NAME));
                log.debug("Added name whitelist for provider {}: {}", provider.getName(), names);
            }
        }

        return filters;
    }

    /** Returns the configured server port. */
    public int getServerPort() {
        return configLoader.getServerPort();
    }

    /** Returns the service URL for dashboard links in block messages. */
    public String getServiceUrl() {
        return configLoader.getServiceUrl();
    }

    /** Returns the heartbeat interval in seconds (0 = disabled). */
    public int getHeartbeatIntervalSeconds() {
        return configLoader.getHeartbeatIntervalSeconds();
    }

    /**
     * Builds a {@link CommitConfig} from the {@code git-proxy.commit} YAML section.
     *
     * <p>All fields are optional; absent keys produce permissive defaults (no domain restriction, no block lists).
     */
    public CommitConfig buildCommitConfig() {
        Map<String, Object> commitMap = configLoader.getCommitConfig();

        // author.email.domain.allow / author.email.local.block
        Map<String, Object> authorMap = getMap(commitMap, "author");
        Map<String, Object> emailMap = getMap(authorMap, "email");

        CommitConfig.DomainConfig domainConfig =
                CommitConfig.DomainConfig.builder().build();
        String domainAllow = getString(getMap(emailMap, "domain"), "allow", null);
        if (domainAllow != null && !domainAllow.isBlank()) {
            domainConfig = CommitConfig.DomainConfig.builder()
                    .allow(Pattern.compile(domainAllow))
                    .build();
        }

        CommitConfig.LocalConfig localConfig =
                CommitConfig.LocalConfig.builder().build();
        String localBlock = getString(getMap(emailMap, "local"), "block", null);
        if (localBlock != null && !localBlock.isBlank()) {
            localConfig = CommitConfig.LocalConfig.builder()
                    .block(Pattern.compile(localBlock))
                    .build();
        }

        CommitConfig.AuthorConfig authorConfig = CommitConfig.AuthorConfig.builder()
                .email(CommitConfig.EmailConfig.builder()
                        .domain(domainConfig)
                        .local(localConfig)
                        .build())
                .build();

        // message.block.literals / message.block.patterns
        CommitConfig.MessageConfig messageConfig = CommitConfig.MessageConfig.builder()
                .block(buildBlockConfig(getMap(getMap(commitMap, "message"), "block")))
                .build();

        // diff.block.literals / diff.block.patterns
        CommitConfig.DiffConfig diffConfig = CommitConfig.DiffConfig.builder()
                .block(buildBlockConfig(getMap(getMap(commitMap, "diff"), "block")))
                .build();

        // secret-scanning.*
        Map<String, Object> secretScanningMap = getMap(commitMap, "secret-scanning");
        CommitConfig.SecretScanningConfig secretScanningConfig = CommitConfig.SecretScanningConfig.builder()
                .enabled(getBoolean(secretScanningMap, "enabled", false))
                .autoInstall(getBoolean(secretScanningMap, "auto-install", true))
                .installDir(getString(secretScanningMap, "install-dir", null))
                .version(getString(secretScanningMap, "version", null))
                .scannerPath(getString(secretScanningMap, "scanner-path", null))
                .configFile(getString(secretScanningMap, "config-file", null))
                .timeoutSeconds(getLong(secretScanningMap, "timeout-seconds", 30L))
                .build();

        CommitConfig config = CommitConfig.builder()
                .author(authorConfig)
                .message(messageConfig)
                .diff(diffConfig)
                .secretScanning(secretScanningConfig)
                .build();

        log.info(
                "Loaded commit config: domain.allow={}, local.block={}, message.literals={}, message.patterns={},"
                        + " diff.literals={}, diff.patterns={}, secretScanning.enabled={}",
                domainAllow != null ? domainAllow : "(none)",
                localBlock != null ? localBlock : "(none)",
                config.getMessage().getBlock().getLiterals().size(),
                config.getMessage().getBlock().getPatterns().size(),
                config.getDiff().getBlock().getLiterals().size(),
                config.getDiff().getBlock().getPatterns().size(),
                secretScanningConfig.isEnabled());

        return config;
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
        String mode = configLoader.getApprovalMode();
        return switch (mode) {
            case "ui" -> {
                log.info("Approval mode: ui (push store polling)");
                yield new UiApprovalGateway(pushStore);
            }
            case "servicenow" -> {
                log.info("Approval mode: servicenow");
                // ServiceNow credentials are expected via GITPROXY_SERVICENOW_URL / GITPROXY_SERVICENOW_CREDENTIALS
                // env vars or a future config section. Use empty strings as placeholder — the gateway will log a
                // warning if they are unset.
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
        String type = configLoader.getDatabaseType();
        Map<String, Object> dbConfig = configLoader.getDatabaseConfig();

        log.info("Initializing push store: type={}", type);

        return switch (type) {
            case "memory" -> PushStoreFactory.inMemory();
            case "h2-mem" -> PushStoreFactory.h2InMemory(getString(dbConfig, "name", "gitproxy"));
            case "h2-file" -> PushStoreFactory.h2File(getString(dbConfig, "path", "./.data/gitproxy"));
            case "sqlite" -> PushStoreFactory.sqlite(getString(dbConfig, "path", "./.data/gitproxy.db"));
            case "postgres" ->
                PushStoreFactory.postgres(
                        getString(dbConfig, "host", "localhost"),
                        getInt(dbConfig, "port", 5432),
                        getString(dbConfig, "name", "gitproxy"),
                        getString(dbConfig, "username", "gitproxy"),
                        getString(dbConfig, "password", "gitproxy"));
            case "mongo" ->
                PushStoreFactory.mongo(
                        getString(dbConfig, "url", "mongodb://gitproxy:gitproxy@localhost:27017"),
                        getString(dbConfig, "name", "gitproxy"));
            default ->
                throw new IllegalArgumentException("Unknown database type: " + type
                        + ". Supported: memory, h2-mem, h2-file, sqlite, postgres, mongo");
        };
    }

    private GitProxyProvider createProvider(String name, String servletPath, String uriStr) {
        String normalizedName = name.toLowerCase().replace("-", "").replace("_", "");

        // Check for known provider types
        if (normalizedName.contains("github")) {
            if (uriStr != null && !uriStr.isEmpty()) {
                return GenericProxyProvider.builder()
                        .name(name)
                        .uri(URI.create(uriStr))
                        .basePath(servletPath)
                        .build();
            }
            return new GitHubProvider(servletPath);
        } else if (normalizedName.contains("gitlab")) {
            if (uriStr != null && !uriStr.isEmpty()) {
                return GenericProxyProvider.builder()
                        .name(name)
                        .uri(URI.create(uriStr))
                        .basePath(servletPath)
                        .build();
            }
            return new GitLabProvider(servletPath);
        } else if (normalizedName.contains("bitbucket")) {
            if (uriStr != null && !uriStr.isEmpty()) {
                return GenericProxyProvider.builder()
                        .name(name)
                        .uri(URI.create(uriStr))
                        .basePath(servletPath)
                        .build();
            }
            return new BitbucketProvider(servletPath);
        } else if (uriStr != null && !uriStr.isEmpty()) {
            // Custom provider with explicit URI
            return GenericProxyProvider.builder()
                    .name(name)
                    .uri(URI.create(uriStr))
                    .basePath(servletPath)
                    .build();
        } else {
            log.warn("Provider '{}' has no URI and is not a known built-in type. Skipping.", name);
            return null;
        }
    }

    private static CommitConfig.BlockConfig buildBlockConfig(Map<String, Object> blockMap) {
        List<String> literals = getStringList(blockMap, "literals");
        List<Pattern> patterns = getStringList(blockMap, "patterns").stream()
                .map(Pattern::compile)
                .collect(Collectors.toList());
        return CommitConfig.BlockConfig.builder()
                .literals(literals)
                .patterns(patterns)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getMap(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private static List<String> getStringList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List) {
            List<String> result = new ArrayList<>();
            for (Object item : (List<Object>) value) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
            return result;
        }
        return Collections.emptyList();
    }

    private static boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return defaultValue;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    private static String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        if (value instanceof String) {
            return (String) value;
        }
        return defaultValue;
    }

    private static long getLong(Map<String, Object> map, String key, long defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return defaultValue;
    }
}
