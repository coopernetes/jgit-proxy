package com.rbc.fogwall.scmapi;

/**
 * The opaque GraphQL global node ID a mutation targets, and which node type it references.
 *
 * @param nodeId the opaque node ID (e.g. {@code "R_kgD..."}) extracted from the mutation's {@code variables}
 * @param nodeType which GraphQL node type {@code nodeId} refers to
 */
public record MutationNodeIdRef(String nodeId, NodeType nodeType) {

    public enum NodeType {
        REPOSITORY,
        ISSUE,
        PULL_REQUEST,
        /**
         * {@code addComment}'s {@code subjectId} may reference either an Issue or a PullRequest — {@code gh} does not
         * distinguish the two in the mutation shape. The resolver's {@code node(id:)} query inline-fragments both
         * types, so the ambiguity does not block resolution.
         */
        ISSUE_OR_PULL_REQUEST
    }
}
