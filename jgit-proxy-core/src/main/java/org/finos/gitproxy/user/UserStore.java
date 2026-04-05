package org.finos.gitproxy.user;

import java.util.List;
import java.util.Optional;

/** Read-access store for proxy users. */
public interface UserStore {

    /** Look up a user by proxy username. */
    Optional<UserEntry> findByUsername(String username);

    /** Look up a user by any of their registered email addresses. */
    Optional<UserEntry> findByEmail(String email);

    /** Look up a user by a provider-specific SCM username (e.g. GitHub login, GitLab username). */
    Optional<UserEntry> findByScmIdentity(String provider, String scmUsername);

    /** Return all known users. */
    List<UserEntry> findAll();
}
