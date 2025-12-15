package org.finos.gitproxy.service;

import lombok.extern.slf4j.Slf4j;

/**
 * Dummy implementation of {@link UserAuthorizationService} that always authorizes users. This implementation should be
 * replaced with a real implementation in production.
 */
@Slf4j
public class DummyUserAuthorizationService implements UserAuthorizationService {

    @Override
    public boolean isUserAuthorizedToPush(String userEmail, String repositoryUrl) {
        log.debug("DummyUserAuthorizationService: Authorizing user {} for repository {}", userEmail, repositoryUrl);
        // Always authorize in dummy implementation
        return true;
    }

    @Override
    public boolean userExists(String userEmail) {
        log.debug("DummyUserAuthorizationService: User {} exists (always true)", userEmail);
        // Always return true in dummy implementation
        return true;
    }

    @Override
    public String getUsernameByEmail(String userEmail) {
        log.debug("DummyUserAuthorizationService: Getting username for {}", userEmail);
        // Extract username from email as a placeholder
        if (userEmail != null && userEmail.contains("@")) {
            return userEmail.substring(0, userEmail.indexOf("@"));
        }
        return userEmail;
    }
}
