package com.rbc.fogwall.config;

import java.time.Duration;
import lombok.Builder;
import lombok.Data;

/**
 * Runtime configuration for SCM OAuth account linking (#40): which identities count as usable for push authorization,
 * and per-provider OAuth app credentials/endpoints.
 */
@Data
@Builder
public class ScmOAuthConfig {

    /** Whether a manually-entered (unverified) SCM identity is still usable for push authorization. */
    public enum IdentityMode {
        /** Today's behaviour: any linked SCM identity is usable, verified or not. Default. */
        PERMISSIVE,
        /** Only OAuth-verified SCM identities are usable for push authorization, on both HTTP and SSH. */
        STRICT;

        public static IdentityMode fromString(String value) {
            if (value == null) return PERMISSIVE;
            return switch (value.trim().toLowerCase()) {
                case "strict" -> STRICT;
                default -> PERMISSIVE;
            };
        }
    }

    @Builder.Default
    private IdentityMode identityMode = IdentityMode.PERMISSIVE;

    /**
     * How often imported SSH keys are re-read from each linked provider. Import otherwise happens once, at link time,
     * so a key the user removes upstream stays usable in fogwall indefinitely — the sweep is what makes revocation take
     * effect. {@link Duration#ZERO} disables it.
     */
    @Builder.Default
    private Duration sshKeyRefreshInterval = DEFAULT_SSH_KEY_REFRESH_INTERVAL;

    /** Keys change rarely and the sweep costs one provider call per linked identity, so it runs weekly. */
    public static final Duration DEFAULT_SSH_KEY_REFRESH_INTERVAL = Duration.ofDays(7);

    public static ScmOAuthConfig defaultConfig() {
        return ScmOAuthConfig.builder().build();
    }
}
