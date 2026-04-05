package org.finos.gitproxy.user;

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

    /** Email addresses associated with this user (for commit author matching). */
    List<String> emails;

    /** SCM identities (provider + username) for this user. */
    List<ScmIdentity> scmIdentities;
}
