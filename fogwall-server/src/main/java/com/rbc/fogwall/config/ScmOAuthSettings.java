package com.rbc.fogwall.config;

import lombok.Data;

/**
 * Binds the {@code scm-oauth:} block in fogwall.yml (#40) — global settings for OAuth account linking that apply across
 * all providers. {@code identity-mode} is translated into the core {@link ScmOAuthConfig} by
 * {@link JettyConfigurationBuilder} and threaded to {@code CheckUserPushPermissionHook}.
 *
 * <p>Per-provider OAuth app registration ({@code client-id}/{@code client-secret-path}) is <em>not</em> here — it lives
 * under {@code providers.<name>.oauth} (see {@link OAuthProviderSettings}), since it is always a property of one
 * specific provider instance, not a separate config tree that has to be kept in sync by name.
 */
@Data
public class ScmOAuthSettings {

    /** {@code permissive} (default) or {@code strict} — see {@link ScmOAuthConfig.IdentityMode}. */
    private String identityMode = "permissive";

    /** Path to a file holding a base64-encoded 32-byte AES-256-GCM key used to encrypt stored OAuth tokens at rest. */
    private String tokenEncryptionKeyPath = "";
}
