package org.finos.gitproxy.service;

import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.user.UserEntry;
import org.finos.gitproxy.user.UserStore;

/**
 * {@link UserAuthorizationService} backed by a {@link UserStore}.
 *
 * <p>A push is authorised when the username can be found in the user store by proxy username or email. Repository-level
 * access control is left for a future issue — all registered users are currently allowed to push to any repo.
 */
@Slf4j
public class LinkedIdentityAuthorizationService implements UserAuthorizationService {

    private final UserStore userStore;

    public LinkedIdentityAuthorizationService(UserStore userStore) {
        this.userStore = userStore;
    }

    @Override
    public boolean isUserAuthorizedToPush(String usernameOrEmail, String repositoryUrl) {
        boolean known = resolve(usernameOrEmail).isPresent();
        log.debug("Authorization check for {} on {}: {}", usernameOrEmail, repositoryUrl, known ? "ALLOW" : "DENY");
        return known;
    }

    @Override
    public boolean userExists(String usernameOrEmail) {
        return resolve(usernameOrEmail).isPresent();
    }

    @Override
    public String getUsernameByEmail(String userEmail) {
        return userStore.findByEmail(userEmail).map(u -> u.getUsername()).orElse(null);
    }

    /** Resolves a user by proxy username, then by email. */
    private Optional<UserEntry> resolve(String value) {
        var byUsername = userStore.findByUsername(value);
        if (byUsername.isPresent()) return byUsername;
        return userStore.findByEmail(value);
    }
}
