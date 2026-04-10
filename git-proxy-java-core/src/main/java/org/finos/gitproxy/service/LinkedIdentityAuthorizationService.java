package org.finos.gitproxy.service;

import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.user.UserEntry;
import org.finos.gitproxy.user.UserStore;

/**
 * {@link UserAuthorizationService} backed by a {@link UserStore}.
 *
 * <p>A push is authorised when the username can be found in the user store by proxy username or email. Used to make
 * access control decisions based on the identity of the user as known to the proxy, which is authorized against an
 * authentication provider (LDAP, OIDC, etc) and who also has their SCM (upstream provider) identity linked in the user
 * store. This is the default implementation of {@link UserAuthorizationService}.
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
        return userStore.findByEmail(userEmail).map(UserEntry::getUsername).orElse(null);
    }

    /** Resolves a user by proxy username, then by email. */
    private Optional<UserEntry> resolve(String value) {
        return userStore.findByUsername(value).or(() -> userStore.findByEmail(value));
    }
}
