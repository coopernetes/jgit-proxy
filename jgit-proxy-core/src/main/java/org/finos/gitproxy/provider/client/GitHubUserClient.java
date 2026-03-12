package org.finos.gitproxy.provider.client;

import java.util.Optional;

/**
 * Client interface for authenticating GitHub users via the GitHub API. Implementations should validate an authorization
 * header by calling the GitHub API and returning user information if the token is valid.
 */
public interface GitHubUserClient {

    /**
     * Retrieves user information from GitHub using the provided authorization header.
     *
     * @param authHeader the Authorization header value (e.g., "Bearer ghp_xxx" or "token xxx")
     * @return the user info if the token is valid, or empty if authentication fails
     */
    Optional<GitHubUserInfo> getUserInfo(String authHeader);
}
