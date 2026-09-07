package com.rbc.fogwall.scmapi;

import java.util.Set;

/**
 * Fixed, hardcoded allowlist of GitHub GraphQL mutation fields the SCM API proxy forwards. Anything not in this set is
 * denied — see docs/internals/SCM_API_PROXY.md's "GitHub allowlist" section for the source list and rationale.
 *
 * <p>Deliberately not config-driven: this list <em>is</em> the security boundary, not an operator knob to tune.
 */
public final class GitHubMutationAllowlist {

    private static final Set<String> ALLOWED_MUTATION_FIELDS = Set.of(
            "createIssue",
            "updateIssue",
            "closeIssue",
            "createPullRequest",
            "updatePullRequest",
            "closePullRequest",
            "addComment",
            // Attribute changes gh sends as follow-ups rather than as fields on the create or update. A label named
            // on `issue create` rides along in createIssue's input, but the same label added by `issue edit` is a
            // separate mutation, as are assignees in every command and reviewers even on create. Denying these
            // leaves the proposal created and the attribute unset, which is worse than refusing the command.
            "replaceActorsForAssignable",
            "addLabelsToLabelable",
            "removeLabelsFromLabelable",
            "requestReviewsByLogin");

    private GitHubMutationAllowlist() {}

    public static boolean isAllowed(String mutationField) {
        return ALLOWED_MUTATION_FIELDS.contains(mutationField);
    }
}
