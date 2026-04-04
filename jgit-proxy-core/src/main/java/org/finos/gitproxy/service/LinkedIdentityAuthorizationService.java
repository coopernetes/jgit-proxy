package org.finos.gitproxy.service;

import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.user.UserEntry;
import org.finos.gitproxy.user.UserStore;

/**
 * {@link UserAuthorizationService} backed by a {@link UserStore}.
 *
 * <p>A push is authorised when the committer email is registered to a known user. Repository-level access control is
 * left for a future issue — all registered users are currently allowed to push to any repo.
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

    /** Resolves a user by push-username, then email, then proxy username. */
    private Optional<UserEntry> resolve(String value) {
        var byPush = userStore.findByPushUsername(value);
        if (byPush.isPresent()) return byPush;
        var byEmail = userStore.findByEmail(value);
        if (byEmail.isPresent()) return byEmail;
        return userStore.findByUsername(value);
    }
}
