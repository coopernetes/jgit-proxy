package org.finos.gitproxy.config;

import lombok.Builder;
import lombok.Data;

/** Configuration for GPG signature validation. */
@Data
@Builder
public class GpgConfig {

    /** Whether GPG signature validation is enabled. */
    @Builder.Default
    private boolean enabled = false;

    /** Whether to require all commits to be signed. */
    @Builder.Default
    private boolean requireSignedCommits = false;

    /**
     * Path to a file containing trusted public keys in ASCII-armored format. If not set, a default dummy key will be
     * used for testing.
     */
    private String trustedKeysFile;

    /** Inline trusted public keys in ASCII-armored format. Used as an alternative to trustedKeysFile. */
    private String trustedKeysInline;

    /**
     * Create a default configuration with GPG validation disabled.
     *
     * @return A default GpgConfig instance
     */
    public static GpgConfig defaultConfig() {
        return GpgConfig.builder().build();
    }
}
