package org.finos.gitproxy.config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.Builder;
import lombok.Data;

/** Configuration for commit validation filters. */
@Data
@Builder
public class CommitConfig {

    /** Controls whether commit identity is verified against the authenticated push user. */
    public enum IdentityVerificationMode {
        /** Block the push when any commit author/committer email is not registered to the push user. */
        STRICT,
        /** Warn the push user but allow the push through. Default. */
        WARN,
        /** Skip identity verification entirely. */
        OFF;

        public static IdentityVerificationMode fromString(String value) {
            if (value == null) return WARN;
            return switch (value.trim().toLowerCase()) {
                case "strict" -> STRICT;
                case "off" -> OFF;
                default -> WARN;
            };
        }
    }

    /**
     * Whether to verify that commit author/committer emails are registered to the authenticated push user. When users
     * are configured, {@code WARN} is the default — mismatches produce a warning but do not block. {@code STRICT}
     * blocks the push. {@code OFF} skips the check entirely.
     */
    @Builder.Default
    private IdentityVerificationMode identityVerification = IdentityVerificationMode.WARN;

    /** Configuration for author email validation. */
    @Builder.Default
    private AuthorConfig author = AuthorConfig.builder().build();

    /** Configuration for commit message validation. */
    @Builder.Default
    private MessageConfig message = MessageConfig.builder().build();

    /** Configuration for diff content scanning. */
    @Builder.Default
    private DiffConfig diff = DiffConfig.builder().build();

    /** Configuration for author validation. */
    @Data
    @Builder
    public static class AuthorConfig {

        /** Configuration for email validation. */
        @Builder.Default
        private EmailConfig email = EmailConfig.builder().build();
    }

    /** Configuration for email validation. */
    @Data
    @Builder
    public static class EmailConfig {

        /** Configuration for email domain validation. */
        @Builder.Default
        private DomainConfig domain = DomainConfig.builder().build();

        /** Configuration for email local part validation. */
        @Builder.Default
        private LocalConfig local = LocalConfig.builder().build();
    }

    /** Configuration for email domain validation. */
    @Data
    @Builder
    public static class DomainConfig {

        /**
         * Regex pattern for allowed email domains. If set, only emails matching this pattern are allowed. Example:
         * Pattern.compile(".*\\.company\\.com$") to allow only company.com domains.
         */
        private Pattern allow;
    }

    /** Configuration for email local part validation. */
    @Data
    @Builder
    public static class LocalConfig {

        /**
         * Regex pattern for blocked email local parts. If set, emails matching this pattern are blocked. Example:
         * Pattern.compile("^(noreply|no-reply)$") to block noreply addresses.
         */
        private Pattern block;
    }

    /** Configuration for commit message validation. */
    @Data
    @Builder
    public static class MessageConfig {

        /** Configuration for blocking specific message patterns. */
        @Builder.Default
        private BlockConfig block = BlockConfig.builder().build();
    }

    /** Configuration for blocking specific message patterns. */
    @Data
    @Builder
    public static class BlockConfig {

        /**
         * List of literal strings that are blocked in commit messages. Messages containing any of these strings will be
         * rejected (case-insensitive).
         */
        @Builder.Default
        private List<String> literals = new ArrayList<>();

        /** List of compiled regex patterns that are blocked in commit messages. */
        @Builder.Default
        private List<Pattern> patterns = new ArrayList<>();
    }

    /** Configuration for diff content scanning. */
    @Data
    @Builder
    public static class DiffConfig {

        /** Configuration for blocking specific patterns in diff content. */
        @Builder.Default
        private BlockConfig block = BlockConfig.builder().build();
    }

    /** Configuration for secret scanning via gitleaks. */
    @Builder.Default
    private SecretScanningConfig secretScanning = SecretScanningConfig.builder().build();

    /** Configuration for secret scanning via an external scanner (gitleaks). */
    @Data
    @Builder
    public static class SecretScanningConfig {

        /** Enable or disable secret scanning. Disabled by default. */
        @Builder.Default
        private boolean enabled = false;

        /**
         * When {@code true} (the default when scanning is enabled), gitleaks is downloaded automatically on first use
         * if it cannot be found via {@code scannerPath}, on the system PATH, or bundled in the JAR. The binary is
         * cached in {@code installDir} across restarts. Set to {@code false} to require an explicit installation.
         */
        @Builder.Default
        private boolean autoInstall = true;

        /**
         * Directory where gitleaks is cached when auto-installed. Defaults to {@code ~/.cache/jgit-proxy/gitleaks}. The
         * directory is created automatically if it does not exist.
         */
        private String installDir;

        /**
         * Version of gitleaks to download when auto-installing. Defaults to the version tested with this release of
         * jgit-proxy. Override to pin a specific version.
         */
        private String version;

        /**
         * Explicit path to a gitleaks binary. Bypasses all other resolution (PATH, JAR, auto-install). Useful for macOS
         * / Windows developer machines or controlled deployments that manage gitleaks externally.
         */
        private String scannerPath;

        /**
         * Path to a gitleaks TOML configuration file. When {@code null} or blank, gitleaks uses its built-in detection
         * rules. Set this to layer in org-specific patterns on top of the defaults. Example:
         *
         * <pre>
         * title = "my-org"
         * [extend]
         * useDefault = true
         * [[rules]]
         * id = "my-org-api-key"
         * regex = '''MY_ORG_[0-9A-Z]{32}'''
         * </pre>
         */
        private String configFile;

        /** Maximum seconds to wait for the gitleaks process before aborting (fail-open). */
        @Builder.Default
        private long timeoutSeconds = 30;
    }

    /**
     * Create a default configuration with no restrictions.
     *
     * @return A default CommitConfig instance
     */
    public static CommitConfig defaultConfig() {
        return CommitConfig.builder().build();
    }
}
