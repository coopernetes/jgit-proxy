package com.rbc.fogwall.scmapi;

import java.util.Optional;

/**
 * Persistent cache mapping a GitLab numeric project ID to the {@code owner/repo} it names.
 *
 * <p>Separate from {@link GitHubNodeIdCache}: a GraphQL node ID and a GitLab project ID are different identifiers from
 * different APIs that happen to share a resolution shape.
 */
public interface GitLabProjectIdCache {

    /** Returns the cached {@code owner/repo} for {@code (provider, projectId)}, or empty if absent or expired. */
    Optional<OwnerRepo> lookup(String provider, String projectId);

    /** Stores or refreshes the {@code owner/repo} resolution for {@code (provider, projectId)}. */
    void store(String provider, String projectId, OwnerRepo ownerRepo);
}
