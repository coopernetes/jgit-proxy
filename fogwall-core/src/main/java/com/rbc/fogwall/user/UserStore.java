package com.rbc.fogwall.user;

import java.util.List;
import java.util.Map;

/**
 * Full user store interface: extends read access with write operations for user, email, and SCM identity management.
 *
 * <p>Mutable backends ({@link JdbcUserStore}, {@link MongoUserStore}, {@link CompositeUserStore}) implement this.
 * {@link StaticUserStore} does not — it only implements {@link ReadOnlyUserStore}. Callers should check
 * {@code instanceof UserStore} and return {@code 501 Not Implemented} when the active store is read-only.
 */
public interface UserStore extends ReadOnlyUserStore {

    // ── email management ────────────────────────────────────────────────────────

    /** Add an email address claim for the given user. No-ops silently if already present. */
    void addEmail(String username, String email);

    /** Remove an email address claim for the given user. No-ops silently if not present. */
    void removeEmail(String username, String email);

    /**
     * Remove all emails locked with the given {@code authSource} for the given user — bypasses the {@link #removeEmail}
     * lock guard, since this is the dedicated OAuth unlink cleanup path ({@code DELETE
     * /api/scm-oauth/{provider}/unlink}), not the generic per-email removal endpoint. No-op if none match.
     */
    void removeEmailsByAuthSource(String username, String authSource);

    // ── SCM identity management ──────────────────────────────────────────────────

    /**
     * Add an SCM identity (provider + SCM username) for the given user. No-ops silently if already registered to this
     * user. Throws {@link ScmIdentityConflictException} if already claimed by a different user.
     */
    void addScmIdentity(String username, String provider, String scmUsername);

    /**
     * Remove an SCM identity for the given user. No-ops silently if not present. Throws
     * {@link VerifiedScmIdentityException} if the identity is OAuth-verified (#40) — use
     * {@link #removeVerifiedScmIdentity} for that case instead (the dedicated OAuth unlink flow).
     */
    void removeScmIdentity(String username, String provider, String scmUsername);

    /**
     * Upsert an OAuth-verified SCM identity (#40) for the given user, replacing any other identity this user has for
     * the same provider (OAuth linking is one identity per provider). Sets {@code verified = true} — this is the only
     * path that does so; {@link #addScmIdentity} always leaves it {@code false}. Throws
     * {@link ScmIdentityConflictException} if the SCM username is already claimed by a different proxy user.
     */
    void upsertVerifiedScmIdentity(String username, String provider, String scmUsername);

    /**
     * Remove the OAuth-verified SCM identity (#40) for the given user and provider, bypassing the
     * {@link #removeScmIdentity} guard — this is the dedicated OAuth unlink path ({@code DELETE
     * /api/scm-oauth/{provider}/unlink}), not the generic manual-entry removal endpoint. No-ops silently if not
     * present. Takes no {@code scmUsername}: {@link #upsertVerifiedScmIdentity} guarantees at most one identity per
     * {@code (username, provider)}, and looking it up first via {@link ReadOnlyUserStore#findByUsername} is unsafe —
     * for a config-declared user, a naive {@code CompositeUserStore} would return the config-only view and never
     * reflect DB-added identities, silently no-opping unlink for exactly the users most likely to be testing it.
     */
    void removeVerifiedScmIdentity(String username, String provider);

    // ── user CRUD ────────────────────────────────────────────────────────────────

    /**
     * Create a new local user. Throws {@link IllegalArgumentException} if the username already exists.
     *
     * @param roles comma-separated roles string, e.g. {@code "USER"} or {@code "USER,ADMIN"}
     */
    void createUser(String username, String passwordHash, String roles);

    /**
     * Delete a user and all their associated data.
     *
     * @throws IllegalArgumentException if the user does not exist
     */
    void deleteUser(String username);

    /**
     * Update the password hash for an existing user.
     *
     * @throws IllegalArgumentException if the user does not exist
     */
    void setPassword(String username, String passwordHash);

    // ── IdP provisioning ─────────────────────────────────────────────────────────

    /**
     * Ensures a user row exists for IdP-authenticated users. No-op if already present. The password is left NULL so the
     * account cannot be used for form login.
     */
    void upsertUser(String username);

    /**
     * Ensures a user row exists and syncs the given roles on every IdP login. Roles are authoritative from the IdP —
     * any existing roles are overwritten so that IdP group changes take effect on next sign-in.
     */
    default void upsertUser(String username, List<String> roles) {
        upsertUser(username);
    }

    /** Inserts or updates an email for a user as locked (owned by the identity provider). */
    void upsertLockedEmail(String username, String email, String authSource);

    // ── enriched queries (for admin UI) ──────────────────────────────────────────

    /** Returns all email entries for a user with their verified, locked, and source status. */
    List<Map<String, Object>> findEmailsWithVerified(String username);

    /** Returns all SCM identity entries for a user with their verified status. */
    List<Map<String, Object>> findScmIdentitiesWithVerified(String username);

    // ── SSH key management ────────────────────────────────────────────────────────

    /**
     * Register an SSH public key for the given user, unlocked (removable via the dashboard).
     *
     * @param username the proxy username
     * @param fingerprint SHA-256 fingerprint in OpenSSH format ({@code SHA256:...}), pre-computed by the caller
     * @param publicKey normalised public key body (algorithm + base64, no comment)
     * @param label optional display label; may be null
     * @return the created {@link SshKeyEntry}
     * @throws IllegalArgumentException if the fingerprint is already registered to another user
     */
    default SshKeyEntry addSshKey(String username, String fingerprint, String publicKey, String label) {
        return addSshKey(username, fingerprint, publicKey, label, false);
    }

    /**
     * Register an SSH public key for the given user, defaulting {@code authSource} to {@code "config"} when locked (the
     * pre-#40 meaning of {@code locked} — declared in the config file).
     *
     * @param locked {@code true} when this key was proven by a verified source rather than self-asserted — a locked key
     *     cannot be removed via {@link #removeSshKey}, mirroring {@link #upsertLockedEmail}'s trust tier
     * @throws SshKeyConflictException if the fingerprint is already registered to a different user
     */
    default SshKeyEntry addSshKey(String username, String fingerprint, String publicKey, String label, boolean locked) {
        return addSshKey(username, fingerprint, publicKey, label, locked, "config");
    }

    /**
     * Register an SSH public key for the given user.
     *
     * @param locked {@code true} when this key was proven by a verified source (e.g. SCM OAuth import, #40) rather than
     *     self-asserted — a locked key cannot be removed via {@link #removeSshKey}, mirroring
     *     {@link #upsertLockedEmail}'s trust tier for OAuth-verified emails
     * @param authSource what locked this key: {@code "config"} or an SCM OAuth provider id (#40) — ignored when
     *     {@code locked} is {@code false}
     * @throws SshKeyConflictException if the fingerprint is already registered to a different user
     */
    SshKeyEntry addSshKey(
            String username, String fingerprint, String publicKey, String label, boolean locked, String authSource);

    /**
     * Remove an SSH key by its ID. No-op if the key does not exist or does not belong to the user.
     *
     * @param username the proxy username (ownership check)
     * @param keyId the key UUID
     * @throws LockedSshKeyException if the key is locked (see {@link #addSshKey(String, String, String, String,
     *     boolean, String)})
     */
    void removeSshKey(String username, String keyId);

    /**
     * Remove all SSH keys locked with the given {@code authSource} for the given user — bypasses the
     * {@link #removeSshKey} lock guard, since this is the dedicated OAuth unlink cleanup path ({@code DELETE
     * /api/scm-oauth/{provider}/unlink}), not the generic per-key removal endpoint. No-op if none match.
     */
    void removeSshKeysByAuthSource(String username, String authSource);

    /** Return all SSH keys registered for the given user. */
    List<SshKeyEntry> findSshKeys(String username);
}
