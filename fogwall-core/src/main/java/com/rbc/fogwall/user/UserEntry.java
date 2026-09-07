package com.rbc.fogwall.user;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/** Domain model for a proxy user loaded from config or the database. */
@Value
@Builder
public class UserEntry {
    String username;

    /** BCrypt password hash. Null when the user authenticates exclusively via an external IdP. */
    String passwordHash;

    /** Email addresses associated with this user (for commit author matching). Never null. */
    @Builder.Default
    List<String> emails = List.of();

    /**
     * SCM identities (provider + username) for this user. Never null — an absent list and an empty one mean the same
     * thing, and letting them differ made "has no identities" reachable in two forms that authorization code had to
     * remember to treat alike.
     */
    @Builder.Default
    List<ScmIdentity> scmIdentities = List.of();

    /** SSH public keys declared in config for this user. Locked — cannot be removed via the dashboard. */
    @Builder.Default
    List<SshKeyEntry> sshKeys = List.of();

    /**
     * Roles granted to this user (e.g. {@code USER}, {@code ADMIN}). Defaults to an empty list which is treated as
     * {@code [USER]} by callers that need at least one role.
     */
    @Builder.Default
    List<String> roles = List.of("USER");

    // A builder default only covers an unset field; these coerce an explicitly-passed null too, so no caller can
    // construct a UserEntry whose lists read as null. Authorization decisions branch on these, and a null that
    // skipped a check would fail open.

    public List<String> getEmails() {
        return emails != null ? emails : List.of();
    }

    public List<ScmIdentity> getScmIdentities() {
        return scmIdentities != null ? scmIdentities : List.of();
    }

    public List<SshKeyEntry> getSshKeys() {
        return sshKeys != null ? sshKeys : List.of();
    }

    public List<String> getRoles() {
        return roles != null ? roles : List.of("USER");
    }
}
