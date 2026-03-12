package org.finos.gitproxy.service;

import lombok.extern.slf4j.Slf4j;

/**
 * Dummy implementation of {@link RepositoryService} that always authorizes repositories. This implementation should be
 * replaced with a real implementation in production.
 */
@Slf4j
public class DummyRepositoryService implements RepositoryService {

    @Override
    public boolean isRepositoryAuthorized(String repositoryUrl) {
        log.debug("DummyRepositoryService: Repository {} is authorized (always true)", repositoryUrl);
        // Always authorize in dummy implementation
        return true;
    }

    @Override
    public boolean repositoryExists(String repositoryUrl) {
        log.debug("DummyRepositoryService: Repository {} exists (always true)", repositoryUrl);
        // Always return true in dummy implementation
        return true;
    }
}
