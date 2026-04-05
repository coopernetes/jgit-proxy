package org.finos.gitproxy.user;

/**
 * Extends {@link UserStore} with write operations for email and SCM identity management.
 *
 * <p>Only mutable backends implement this — {@link JdbcUserStore} does, {@link StaticUserStore} does not. Callers
 * should check {@code instanceof MutableUserStore} and return {@code 501 Not Implemented} when the active store is
 * read-only.
 */
public interface MutableUserStore extends UserStore {

    /** Add an email address claim for the given user. No-ops silently if already present. */
    void addEmail(String username, String email);

    /** Remove an email address claim for the given user. No-ops silently if not present. */
    void removeEmail(String username, String email);

    /** Add an SCM identity (provider + SCM username) for the given user. No-ops silently if already present. */
    void addScmIdentity(String username, String provider, String scmUsername);

    /** Remove an SCM identity for the given user. No-ops silently if not present. */
    void removeScmIdentity(String username, String provider, String scmUsername);
}
