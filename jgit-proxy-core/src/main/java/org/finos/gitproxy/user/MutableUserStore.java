package org.finos.gitproxy.user;

import java.util.List;
import java.util.Map;

/**
 * Extends {@link UserStore} with write operations for user, email, and SCM identity management.
 *
 * <p>Only mutable backends implement this — {@link JdbcUserStore} and {@link CompositeUserStore} do,
 * {@link StaticUserStore} does not. Callers should check {@code instanceof MutableUserStore} and return {@code 501 Not
 * Implemented} when the active store is read-only.
 */
public interface MutableUserStore extends UserStore {

    // ── email management ────────────────────────────────────────────────────────

    /** Add an email address claim for the given user. No-ops silently if already present. */
    void addEmail(String username, String email);

    /** Remove an email address claim for the given user. No-ops silently if not present. */
    void removeEmail(String username, String email);

    // ── SCM identity management ──────────────────────────────────────────────────

    /**
     * Add an SCM identity (provider + SCM username) for the given user. No-ops silently if already registered to this
     * user. Throws {@link ScmIdentityConflictException} if already claimed by a different user.
     */
    void addScmIdentity(String username, String provider, String scmUsername);

    /** Remove an SCM identity for the given user. No-ops silently if not present. */
    void removeScmIdentity(String username, String provider, String scmUsername);

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

    /** Inserts or updates an email for a user as locked (owned by the identity provider). */
    void upsertLockedEmail(String username, String email, String authSource);

    // ── enriched queries (for admin UI) ──────────────────────────────────────────

    /** Returns all email entries for a user with their verified, locked, and source status. */
    List<Map<String, Object>> findEmailsWithVerified(String username);

    /** Returns all SCM identity entries for a user with their verified status. */
    List<Map<String, Object>> findScmIdentitiesWithVerified(String username);
}
