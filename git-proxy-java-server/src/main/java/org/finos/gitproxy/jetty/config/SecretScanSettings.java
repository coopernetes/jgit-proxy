package org.finos.gitproxy.jetty.config;

import lombok.Data;

/**
 * Binds the {@code secret-scan:} block in git-proxy.yml. Controls gitleaks secret scanning applied to every push diff.
 *
 * <p>This is a push-level check, distinct from the per-commit checks under {@code commit:}.
 *
 * <p>Binary resolution order (first match wins):
 *
 * <ol>
 *   <li>{@code scanner-path} — explicit path, bypasses all other resolution
 *   <li>Version-pinned download — if {@code version} is set and {@code auto-install: true}
 *   <li>Bundled JAR binary — always present in standard builds
 *   <li>System PATH — gitleaks already installed on host/container
 * </ol>
 */
@Data
public class SecretScanSettings {
    private boolean enabled = false;
    private boolean autoInstall = true;
    private String installDir = "";
    private String version = "";
    private String scannerPath = "";

    /**
     * Path to an external gitleaks TOML configuration file. Ignored when {@code inlineConfig} is also set — use one or
     * the other. If both are set, {@code inlineConfig} wins and a warning is logged.
     */
    private String configFile = "";

    /**
     * Inline gitleaks TOML configuration. Takes precedence over {@code configFile}. Useful for hot-reload scenarios
     * where editing a file on disk is inconvenient — change this value in an externalized config source and trigger a
     * reload via {@code POST /api/config/reload?section=secret-scan}.
     *
     * <p>Content must be valid gitleaks TOML. Example:
     *
     * <pre>
     * title = "my-org"
     * [extend]
     * useDefault = true
     * [[rules]]
     * id = "my-org-api-key"
     * regex = '''MY_ORG_[A-Z0-9]{32}'''
     * </pre>
     */
    private String inlineConfig = "";

    private long timeoutSeconds = 30;
}
