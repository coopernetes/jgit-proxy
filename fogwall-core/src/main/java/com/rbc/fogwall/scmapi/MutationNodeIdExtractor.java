package com.rbc.fogwall.scmapi;

import graphql.language.Argument;
import graphql.language.Field;
import graphql.language.Value;
import graphql.language.VariableReference;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Extracts the opaque node ID an allowlisted mutation targets, and which node type it references.
 *
 * <p>The document decides which variable holds the target; {@code variables} only supplies its value. Reading a fixed
 * path out of {@code variables} instead would authorize a value the upstream may never see, since GraphQL ignores
 * variables an operation does not declare — a request could carry a permitted ID under {@code input} while the
 * operation passes a different one. The value authorized here is the value executed.
 *
 * <p>The {@code input} argument has to be a variable reference, which is what {@code gh} sends; an inline object is
 * refused.
 *
 * <p>The argument holding the ID differs per mutation field — see docs/internals/SCM_API_PROXY.md's "Mutation → node-ID
 * map" table, verified against live {@code gh} traffic.
 */
public final class MutationNodeIdExtractor {

    /** The {@code input} member holding the target, and the node type it refers to. */
    private record NodeIdKey(String inputField, MutationNodeIdRef.NodeType nodeType) {}

    private static final String INPUT_ARGUMENT = "input";

    private static final Map<String, NodeIdKey> NODE_ID_KEYS = Map.ofEntries(
            Map.entry("createIssue", new NodeIdKey("repositoryId", MutationNodeIdRef.NodeType.REPOSITORY)),
            Map.entry("createPullRequest", new NodeIdKey("repositoryId", MutationNodeIdRef.NodeType.REPOSITORY)),
            Map.entry("updateIssue", new NodeIdKey("id", MutationNodeIdRef.NodeType.ISSUE)),
            Map.entry("closeIssue", new NodeIdKey("issueId", MutationNodeIdRef.NodeType.ISSUE)),
            Map.entry("addComment", new NodeIdKey("subjectId", MutationNodeIdRef.NodeType.ISSUE_OR_PULL_REQUEST)),
            Map.entry("updatePullRequest", new NodeIdKey("pullRequestId", MutationNodeIdRef.NodeType.PULL_REQUEST)),
            Map.entry("closePullRequest", new NodeIdKey("pullRequestId", MutationNodeIdRef.NodeType.PULL_REQUEST)),
            // Attribute follow-ups. Each names its target by the generic capability it acts through, not by the
            // concrete type: an assignable and a labelable are both "issue or pull request", so the same node ID
            // resolution path already covers them.
            Map.entry(
                    "replaceActorsForAssignable",
                    new NodeIdKey("assignableId", MutationNodeIdRef.NodeType.ISSUE_OR_PULL_REQUEST)),
            Map.entry(
                    "addLabelsToLabelable",
                    new NodeIdKey("labelableId", MutationNodeIdRef.NodeType.ISSUE_OR_PULL_REQUEST)),
            Map.entry(
                    "removeLabelsFromLabelable",
                    new NodeIdKey("labelableId", MutationNodeIdRef.NodeType.ISSUE_OR_PULL_REQUEST)),
            Map.entry(
                    "requestReviewsByLogin", new NodeIdKey("pullRequestId", MutationNodeIdRef.NodeType.PULL_REQUEST)));

    private MutationNodeIdExtractor() {}

    /**
     * Extracts the node ID reference the mutation targets.
     *
     * <p>Empty whenever the target cannot be established with certainty — an unknown field, a missing or non-string ID,
     * an argument shape this does not model, or a variable the request never supplied. The caller refuses the request
     * on empty; there is no shape here whose meaning is worth guessing at.
     */
    public static Optional<MutationNodeIdRef> extract(Field mutation, JsonNode variables) {
        NodeIdKey key = NODE_ID_KEYS.get(mutation.getName());
        if (key == null) {
            return Optional.empty();
        }
        // The argument has to be $input by name: that is what every captured request sends, and pinning it makes
        // variables.input the only place the target can be.
        if (!(argument(mutation) instanceof VariableReference reference)
                || !INPUT_ARGUMENT.equals(reference.getName())) {
            return Optional.empty();
        }
        JsonNode input = variables == null ? null : variables.get(INPUT_ARGUMENT);
        if (input == null) {
            return Optional.empty();
        }
        JsonNode id = input.get(key.inputField());
        return id != null && id.isString()
                ? Optional.of(new MutationNodeIdRef(id.asString(), key.nodeType()))
                : Optional.empty();
    }
    /** The mutation's {@code input} argument, or null when it carries none. */
    private static Value<?> argument(Field field) {
        for (Argument argument : field.getArguments()) {
            if (INPUT_ARGUMENT.equals(argument.getName())) {
                return argument.getValue();
            }
        }
        return null;
    }
}
