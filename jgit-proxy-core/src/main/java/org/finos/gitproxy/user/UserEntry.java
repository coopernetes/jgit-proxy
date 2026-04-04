package org.finos.gitproxy.user;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/** Domain model for a proxy user loaded from config or the database. */
@Value
@Builder
public class UserEntry {
    String username;

    /** BCrypt password hash. */
    String passwordHash;

    /** Email addresses associated with this user (for commit author matching). */
    List<String> emails;

    /**
     * Push usernames — the HTTP Basic-auth usernames this user may push as. Used to authorize git pushes before SCM
     * OAuth is available. Examples: corporate username, GitHub handle, test script username.
     */
    List<String> pushUsernames;

    /** SCM identities (provider + username) for this user. */
    List<ScmIdentity> scmIdentities;
}
