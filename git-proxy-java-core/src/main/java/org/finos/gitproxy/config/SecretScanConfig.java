package org.finos.gitproxy.config;

import lombok.Builder;
import lombok.Data;

/**
 * Runtime configuration for gitleaks secret scanning. Applied once per push.
 *
 * <p>Distinct from {@link CommitConfig} which operates per-commit. This check runs once per push.
 *
 * <p>Hot-reloadable via {@code POST /api/config/reload?section=secret-scanning}.
 */
@Data
@Builder
public class SecretScanConfig {

    /** Enable or disable secret scanning. Disabled by default. */
    @Builder.Default
    private boolean enabled = false;

    /**
     * When {@code true} (the default when scanning is enabled), gitleaks is downloaded automatically on first use if it
     * cannot be found via {@code scannerPath}, on the system PATH, or bundled in the JAR.
     */
    @Builder.Default
    private boolean autoInstall = true;

    /** Directory where gitleaks is cached when auto-installed. Defaults to {@code ~/.cache/git-proxy-java/gitleaks}. */
    private String installDir;

    /** Version of gitleaks to download when auto-installing. */
    private String version;

    /** Explicit path to a gitleaks binary. Bypasses all other resolution. */
    private String scannerPath;

    /**
     * Path to a gitleaks TOML configuration file. Ignored when {@code inlineConfig} is set — use one or the other. If
     * both are set, {@code inlineConfig} wins and a warning is logged.
     */
    private String configFile;

    /**
     * Inline gitleaks TOML configuration. Takes precedence over {@code configFile}. Useful for hot-reload scenarios
     * where editing a file on disk is inconvenient. Content must be valid gitleaks TOML.
     */
    private String inlineConfig;

    /** Maximum seconds to wait for the gitleaks process before aborting (fail-open). */
    @Builder.Default
    private long timeoutSeconds = 30;

    public static SecretScanConfig defaultConfig() {
        return SecretScanConfig.builder().build();
    }
}
