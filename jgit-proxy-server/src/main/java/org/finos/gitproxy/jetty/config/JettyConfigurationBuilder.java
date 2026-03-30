package org.finos.gitproxy.jetty.config;

import java.net.URI;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
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

            int order = getInt(whitelistConfig, "order", 1100);

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
}
