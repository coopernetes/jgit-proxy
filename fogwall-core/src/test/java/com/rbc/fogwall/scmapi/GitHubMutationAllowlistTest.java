package com.rbc.fogwall.scmapi;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GitHubMutationAllowlistTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "createIssue",
                "updateIssue",
                "closeIssue",
                "createPullRequest",
                "updatePullRequest",
                "closePullRequest",
                "addComment",
                // Attribute follow-ups gh sends after the create or update, verified from live traffic.
                "replaceActorsForAssignable",
                "addLabelsToLabelable",
                "removeLabelsFromLabelable",
                "requestReviewsByLogin"
            })
    void allowsEachInScopeMutationField(String field) {
        assertTrue(GitHubMutationAllowlist.isAllowed(field));
    }

    /**
     * Requesting a review is part of proposing a change; performing one is not. The two are separate mutations, so
     * admitting the request does not admit the verdict.
     */
    @Test
    void requestingAReviewIsAllowedButSubmittingOneIsNot() {
        assertTrue(GitHubMutationAllowlist.isAllowed("requestReviewsByLogin"));
        assertFalse(GitHubMutationAllowlist.isAllowed("addPullRequestReview"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "deleteIssue",
                "mergePullRequest",
                // Review is out of scope — reviewers use the SCM's own UI, so fogwall never forwards these.
                "addPullRequestReview",
                "submitPullRequestReview",
                "addPullRequestReviewComment",
                "createRepository",
                "updateRepository",
                "deleteRepository",
                "createIssue ", // trailing whitespace must not fuzzy-match
                ""
            })
    void deniesAnyUnrecognizedMutationField(String field) {
        assertFalse(GitHubMutationAllowlist.isAllowed(field));
    }
}
