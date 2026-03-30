package org.finos.gitproxy.jetty.config;

import java.io.InputStream;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads and merges YAML configuration for the Jetty server implementation.
 *
 * <p>Configuration is loaded from:
 *
 * <ol>
 *   <li>{@code git-proxy.yml} - Base configuration (shipped with the jar)
 *   <li>{@code git-proxy-local.yml} - Local overrides (optional, for development)
 *   <li>Environment variables with {@code GITPROXY_} prefix (highest priority)
 * </ol>
 *
 * <p>Environment variable mapping uses the {@code GITPROXY_} prefix followed by the configuration key in uppercase with
 * underscores. For example:
 *
 * <ul>
 *   <li>{@code GITPROXY_SERVER_PORT} overrides {@code server.port}
 *   <li>{@code GITPROXY_GITPROXY_BASEPATH} overrides {@code git-proxy.base-path}
 * </ul>
 *
 * <p>Whitelist configuration is not supported via environment variables due to its complex nested structure.
 */
@Slf4j
public class JettyConfigurationLoader {

    private static final String BASE_CONFIG = "git-proxy.yml";
    private static final String LOCAL_CONFIG = "git-proxy-local.yml";
    private static final String ENV_PREFIX = "GITPROXY_";

    private final Map<String, Object> config;

    public JettyConfigurationLoader() {
        this.config = load();
    }

    /** Loads configuration from YAML files and environment variables. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> load() {
        Yaml yaml = new Yaml();
        Map<String, Object> merged = new LinkedHashMap<>();

        // Load base config
        try (InputStream baseStream = getClass().getClassLoader().getResourceAsStream(BASE_CONFIG)) {
            if (baseStream != null) {
                Map<String, Object> baseConfig = yaml.load(baseStream);
                if (baseConfig != null) {
                    merged = baseConfig;
                    log.info("Loaded base configuration from {}", BASE_CONFIG);
                }
            } else {
                log.warn("Base configuration file {} not found on classpath", BASE_CONFIG);
            }
        } catch (Exception e) {
            log.error("Failed to load base configuration from {}", BASE_CONFIG, e);
        }

        // Load local overrides
        try (InputStream localStream = getClass().getClassLoader().getResourceAsStream(LOCAL_CONFIG)) {
            if (localStream != null) {
                Map<String, Object> localConfig = yaml.load(localStream);
                if (localConfig != null) {
                    deepMerge(merged, localConfig);
                    log.info("Loaded local configuration overrides from {}", LOCAL_CONFIG);
                }
            } else {
                log.debug("No local configuration file {} found (this is normal)", LOCAL_CONFIG);
            }
        } catch (Exception e) {
            log.warn("Failed to load local configuration from {}", LOCAL_CONFIG, e);
        }

        // Apply environment variable overrides
        applyEnvironmentOverrides(merged);

        return merged;
    }

    /**
     * Applies environment variable overrides with the GITPROXY_ prefix. Whitelist configuration is excluded due to its
     * complex structure.
     */
    @SuppressWarnings("unchecked")
    private void applyEnvironmentOverrides(Map<String, Object> config) {
        Map<String, String> env = System.getenv();

        // GITPROXY_SERVER_PORT -> server.port
        String portOverride = env.get(ENV_PREFIX + "SERVER_PORT");
        if (portOverride != null) {
            Map<String, Object> serverMap =
                    (Map<String, Object>) config.computeIfAbsent("server", k -> new LinkedHashMap<>());
            serverMap.put("port", Integer.parseInt(portOverride));
            log.info("Applied environment override: GITPROXY_SERVER_PORT={}", portOverride);
        }

        // GITPROXY_GITPROXY_BASEPATH -> git-proxy.base-path
        String basePathOverride = env.get(ENV_PREFIX + "GITPROXY_BASEPATH");
        if (basePathOverride != null) {
            Map<String, Object> gitProxyMap =
                    (Map<String, Object>) config.computeIfAbsent("git-proxy", k -> new LinkedHashMap<>());
            gitProxyMap.put("base-path", basePathOverride);
            log.info("Applied environment override: GITPROXY_GITPROXY_BASEPATH={}", basePathOverride);
        }

        // GITPROXY_PROVIDERS_<NAME>_ENABLED -> git-proxy.providers.<name>.enabled
        for (Map.Entry<String, String> entry : env.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(ENV_PREFIX + "PROVIDERS_") && key.endsWith("_ENABLED")) {
                String providerName = key.substring(
                                (ENV_PREFIX + "PROVIDERS_").length(), key.length() - "_ENABLED".length())
                        .toLowerCase()
                        .replace('_', '-');
                Map<String, Object> gitProxyMap =
                        (Map<String, Object>) config.computeIfAbsent("git-proxy", k -> new LinkedHashMap<>());
                Map<String, Object> providersMap =
                        (Map<String, Object>) gitProxyMap.computeIfAbsent("providers", k -> new LinkedHashMap<>());
                Map<String, Object> providerMap =
                        (Map<String, Object>) providersMap.computeIfAbsent(providerName, k -> new LinkedHashMap<>());
                providerMap.put("enabled", Boolean.parseBoolean(entry.getValue()));
                log.info("Applied environment override: {}={}", key, entry.getValue());
            }
        }
    }

    /**
     * Deep merges the source map into the target map. Values from source override those in target for non-map values.
     * Maps are merged recursively.
     */
    @SuppressWarnings("unchecked")
    private void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object sourceValue = entry.getValue();
            Object targetValue = target.get(key);

            if (sourceValue instanceof Map && targetValue instanceof Map) {
                deepMerge((Map<String, Object>) targetValue, (Map<String, Object>) sourceValue);
            } else {
                target.put(key, sourceValue);
            }
        }
    }

    /** Returns the server port configuration. */
    @SuppressWarnings("unchecked")
    public int getServerPort() {
        Map<String, Object> serverMap = (Map<String, Object>) config.get("server");
        if (serverMap != null && serverMap.containsKey("port")) {
            return ((Number) serverMap.get("port")).intValue();
        }
        return 8080;
    }

    /** Returns the base path configuration. */
    @SuppressWarnings("unchecked")
    public String getBasePath() {
        Map<String, Object> gitProxy = (Map<String, Object>) config.get("git-proxy");
        if (gitProxy != null && gitProxy.containsKey("base-path")) {
            return (String) gitProxy.get("base-path");
        }
        return "";
    }

    /** Returns the providers configuration as a map of provider name to provider settings. */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> getProviders() {
        Map<String, Object> gitProxy = (Map<String, Object>) config.get("git-proxy");
        if (gitProxy != null && gitProxy.containsKey("providers")) {
            Object providers = gitProxy.get("providers");
            if (providers instanceof Map) {
                return (Map<String, Map<String, Object>>) providers;
            }
        }
        return Collections.emptyMap();
    }

    /** Returns the whitelist filters configuration as a list of whitelist entries. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getWhitelistFilters() {
        Map<String, Object> gitProxy = (Map<String, Object>) config.get("git-proxy");
        if (gitProxy != null && gitProxy.containsKey("filters")) {
            Map<String, Object> filters = (Map<String, Object>) gitProxy.get("filters");
            if (filters != null && filters.containsKey("whitelists")) {
                Object whitelists = filters.get("whitelists");
                if (whitelists instanceof List) {
                    return (List<Map<String, Object>>) whitelists;
                }
            }
        }
        return Collections.emptyList();
    }

    /** Returns the database type (memory, h2-mem, h2-file, sqlite, postgres, mongo). */
    @SuppressWarnings("unchecked")
    public String getDatabaseType() {
        Map<String, Object> db = (Map<String, Object>) config.get("database");
        if (db != null && db.containsKey("type")) {
            return (String) db.get("type");
        }
        return "h2-mem";
    }

    /** Returns the database configuration map. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getDatabaseConfig() {
        Map<String, Object> db = (Map<String, Object>) config.get("database");
        return db != null ? db : Collections.emptyMap();
    }

    /** Returns the service URL used for dashboard links in block messages and sideband output. */
    @SuppressWarnings("unchecked")
    public String getServiceUrl() {
        Map<String, Object> gitProxy = (Map<String, Object>) config.get("git-proxy");
        if (gitProxy != null && gitProxy.containsKey("service-url")) {
            Object val = gitProxy.get("service-url");
            if (val instanceof String) return (String) val;
        }
        return "http://localhost:" + getServerPort();
    }

    /** Returns the full merged configuration map (for debugging/testing). */
    public Map<String, Object> getRawConfig() {
        return Collections.unmodifiableMap(config);
    }
}
