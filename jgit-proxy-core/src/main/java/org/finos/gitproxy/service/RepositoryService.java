package org.finos.gitproxy.service;

/**
 * Service interface for repository operations. Implementations should provide repository metadata and authorization
 * information.
 */
public interface RepositoryService {

    /**
     * Check if a repository is in the authorized list.
     *
     * @param repositoryUrl The URL of the repository
     * @return true if the repository is authorized, false otherwise
     */
    boolean isRepositoryAuthorized(String repositoryUrl);

    /**
     * Check if a repository exists.
     *
     * @param repositoryUrl The URL of the repository
     * @return true if the repository exists, false otherwise
     */
    boolean repositoryExists(String repositoryUrl);
}
