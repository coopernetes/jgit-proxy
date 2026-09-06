package com.rbc.fogwall.scmapi;

import java.util.Optional;

/** Persistent cache mapping an opaque GraphQL node ID to the {@code owner/repo} it belongs to. */
public interface GitHubNodeIdCache {

    /** Returns the cached {@code owner/repo} for {@code (provider, nodeId)}, or empty if absent or expired. */
    Optional<OwnerRepo> lookup(String provider, String nodeId);

    /** Stores or refreshes the {@code owner/repo} resolution for {@code (provider, nodeId)}. */
    void store(String provider, String nodeId, OwnerRepo ownerRepo);
}
