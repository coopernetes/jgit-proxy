package com.rbc.fogwall.user;

import java.time.Instant;
import java.util.Optional;

/**
 * Stores the OAuth tokens SCM account linking obtains, keyed by {@code (username, provider)}.
 *
 * <p>Tokens arrive already encrypted by the caller (see {@code com.rbc.fogwall.crypto.TokenCipher}); an implementation
 * persists opaque bytes and never touches key material.
 *
 * <p>Both database families implement this. Without a Mongo implementation, account linking and the SSH key refresh
 * were unavailable on Mongo deployments, which also made {@code scm-oauth.identity-mode: strict} unsatisfiable there.
 */
public interface ScmOAuthTokenStore {

    /** Upserts the token for {@code (username, provider)}, replacing any prior token for the same pair. */
    void save(
            String username,
            String provider,
            byte[] encryptedAccessToken,
            byte[] encryptedRefreshToken,
            String scopes,
            Instant expiresAt);

    /**
     * Returns the stored encrypted access token for {@code (username, provider)}, if any. The caller decrypts it — for
     * revoking the grant upstream, or for reading the account's registered SSH keys.
     */
    Optional<byte[]> findAccessToken(String username, String provider);

    /** Removes the stored token for {@code (username, provider)}, if any. No-ops when none exists. */
    void remove(String username, String provider);
}
