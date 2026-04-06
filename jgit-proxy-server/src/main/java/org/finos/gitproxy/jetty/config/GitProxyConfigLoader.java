package org.finos.gitproxy.jetty.config;

import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.github.gestalt.config.Gestalt;
import org.github.gestalt.config.builder.GestaltBuilder;
import org.github.gestalt.config.exceptions.GestaltException;
import org.github.gestalt.config.source.ClassPathConfigSourceBuilder;
import org.github.gestalt.config.source.MapConfigSourceBuilder;

/**
 * Loads {@link GitProxyConfig} from YAML files and environment variable overrides using Gestalt.
 *
 * <p>Source priority (lowest → highest):
 *
 * <ol>
 *   <li>{@code git-proxy.yml} — base defaults shipped with the jar
 *   <li>Profile configs named in {@code GITPROXY_CONFIG_PROFILES} — comma-separated list of profile names; each loads
 *       {@code git-proxy-{profile}.yml} from the classpath in order (optional, silently skipped if absent). Later
 *       profiles take priority over earlier ones.
 *   <li>Environment variables with {@code GITPROXY_} prefix (highest priority)
 * </ol>
 *
 * <p>Examples:
 *
 * <ul>
 *   <li>{@code GITPROXY_CONFIG_PROFILES=local} — local dev (loads {@code git-proxy-local.yml})
 *   <li>{@code GITPROXY_CONFIG_PROFILES=docker-default,ldap} — Docker + LDAP auth
 * </ul>
 *
 * <p>Environment variable naming: strip the {@code GITPROXY_} prefix, lowercase, replace {@code _} with {@code .} to
 * get the config path. Examples:
 *
 * <ul>
 *   <li>{@code GITPROXY_SERVER_PORT=9090} → {@code server.port}
 *   <li>{@code GITPROXY_DATABASE_TYPE=postgres} → {@code database.type}
 *   <li>{@code GITPROXY_PROVIDERS_GITHUB_ENABLED=false} → {@code providers.github.enabled}
 * </ul>
 */
@Slf4j
public final class GitProxyConfigLoader {

    private static final String BASE_CONFIG = "git-proxy.yml";
    private static final String ENV_PREFIX = "GITPROXY_";
    private static final String PROFILES_ENV_VAR = "GITPROXY_CONFIG_PROFILES";

    private GitProxyConfigLoader() {}

    /**
     * Loads and merges configuration from all sources.
     *
     * @return fully-populated {@link GitProxyConfig}
     * @throws GestaltException if the base config cannot be parsed
     */
    public static GitProxyConfig load() throws GestaltException {
        var builder = new GestaltBuilder()
                .setTreatMissingValuesAsErrors(false)
                .setTreatMissingDiscretionaryValuesAsErrors(false);

        builder.addSource(
                ClassPathConfigSourceBuilder.builder().setResource(BASE_CONFIG).build());
        log.info("Loaded base configuration from {}", BASE_CONFIG);

        // Profile configs: GITPROXY_CONFIG_PROFILES=docker-default,ldap
        // loads git-proxy-docker-default.yml then git-proxy-ldap.yml (later = higher priority)
        String profilesEnv = System.getenv(PROFILES_ENV_VAR);
        if (profilesEnv != null && !profilesEnv.isBlank()) {
            for (String profile : profilesEnv.split(",")) {
                String profileConfig = "git-proxy-" + profile.trim() + ".yml";
                if (GitProxyConfigLoader.class.getClassLoader().getResource(profileConfig) != null) {
                    builder.addSource(ClassPathConfigSourceBuilder.builder()
                            .setResource(profileConfig)
                            .build());
                    log.info("Loaded profile configuration from {}", profileConfig);
                } else {
                    log.debug("Profile config {} not found on classpath (skipped)", profileConfig);
                }
            }
        }

        // Env var overrides: GITPROXY_SERVER_PORT → server.port
        Map<String, String> envOverrides = buildEnvOverrides();
        if (!envOverrides.isEmpty()) {
            builder.addSource(MapConfigSourceBuilder.builder()
                    .setCustomConfig(envOverrides)
                    .build());
            log.info("Applied {} environment variable override(s) with prefix {}", envOverrides.size(), ENV_PREFIX);
        }

        Gestalt gestalt = builder.build();
        gestalt.loadConfigs();

        return gestalt.getConfig("", GitProxyConfig.class);
    }

    private static Map<String, String> buildEnvOverrides() {
        Map<String, String> overrides = new HashMap<>();
        System.getenv().forEach((key, value) -> {
            if (key.startsWith(ENV_PREFIX) && !key.equals(PROFILES_ENV_VAR)) {
                String configPath =
                        key.substring(ENV_PREFIX.length()).toLowerCase().replace('_', '.');
                overrides.put(configPath, value);
                log.debug("Env override: {} → {}", key, configPath);
            }
        });
        return overrides;
    }
}
