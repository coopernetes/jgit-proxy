package org.finos.gitproxy.user;

import java.util.List;
import java.util.Optional;

/** Read-access store for proxy users. */
public interface UserStore {

    /** Look up a user by username. */
    Optional<UserEntry> findByUsername(String username);

    /** Look up a user by any of their registered email addresses. */
    Optional<UserEntry> findByEmail(String email);

    /** Look up a user by any of their registered push usernames. */
    Optional<UserEntry> findByPushUsername(String pushUsername);

    /** Return all known users. */
    List<UserEntry> findAll();
}
