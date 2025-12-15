package org.finos.gitproxy.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/** Configuration for secret scanning filters. */
@Data
@Builder
public class SecretScanningConfig {

    /** Whether secret scanning is enabled. */
    @Builder.Default
    private boolean enabled = true;

    /**
     * List of regex patterns to detect secrets. Each pattern should match potential secrets like API keys, passwords,
     * tokens, etc.
     */
    @Builder.Default
    private List<String> patterns = new ArrayList<>();

    /** Maximum file size to scan in bytes. Files larger than this will be skipped. */
    @Builder.Default
    private long maxFileSizeBytes = 1024 * 1024; // 1MB default

    /**
     * List of file extensions to skip during scanning (e.g., ".jpg", ".png", ".zip"). Binary files are typically
     * excluded.
     */
    @Builder.Default
    private List<String> excludedExtensions = List.of(".jpg", ".jpeg", ".png", ".gif", ".zip", ".jar", ".class");

    /**
     * Create a default configuration with common secret patterns.
     *
     * @return A default SecretScanningConfig instance
     */
    public static SecretScanningConfig defaultConfig() {
        List<String> defaultPatterns = new ArrayList<>();

        // AWS Keys
        defaultPatterns.add("AKIA[0-9A-Z]{16}"); // AWS Access Key ID
        defaultPatterns.add("aws(.{0,20})?['\"][0-9a-zA-Z/+]{40}['\"]"); // AWS Secret Key

        // Generic API Keys
        defaultPatterns.add("api[_-]?key['\"]?\\s*[:=]\\s*['\"][0-9a-zA-Z]{32,45}['\"]");

        // Generic Passwords
        defaultPatterns.add("password['\"]?\\s*[:=]\\s*['\"][^'\"\\s]{8,}['\"]");

        // Private Keys
        defaultPatterns.add("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----");

        // Generic Tokens
        defaultPatterns.add("token['\"]?\\s*[:=]\\s*['\"][0-9a-zA-Z]{20,}['\"]");

        // Database Connection Strings
        defaultPatterns.add("(jdbc|mongodb|mysql|postgresql)://[^\\s]+:[^\\s]+@");

        return SecretScanningConfig.builder().patterns(defaultPatterns).build();
    }
}
