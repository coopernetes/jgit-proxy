package org.finos.gitproxy.service;

/**
 * Service interface for user authorization. Implementations should determine whether a user is authorized to perform
 * operations on repositories.
 */
public interface UserAuthorizationService {

    /**
     * Check if a user is authorized to push to a specific repository.
     *
     * @param userEmail The email address of the user
     * @param repositoryUrl The URL of the repository
     * @return true if the user is authorized, false otherwise
     */
    boolean isUserAuthorizedToPush(String userEmail, String repositoryUrl);

    /**
     * Check if a user exists in the system.
     *
     * @param userEmail The email address of the user
     * @return true if the user exists, false otherwise
     */
    boolean userExists(String userEmail);

    /**
     * Get the username associated with an email address.
     *
     * @param userEmail The email address of the user
     * @return The username, or null if not found
     */
    String getUsernameByEmail(String userEmail);
}
