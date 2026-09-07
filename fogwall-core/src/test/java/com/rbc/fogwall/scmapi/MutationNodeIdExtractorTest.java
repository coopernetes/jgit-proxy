package com.rbc.fogwall.scmapi;

import static org.junit.jupiter.api.Assertions.*;

import graphql.language.Field;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MutationNodeIdExtractorTest {

    private static final JsonMapper MAPPER = new JsonMapper();

    /** Parses a whole request the way the gate filter does, so the tests exercise the real document → target path. */
    private static java.util.Optional<MutationNodeIdRef> extract(String query, String variablesJson) {
        Field mutation = GraphQlMutationParser.selectMutationField(query, null).orElseThrow();
        JsonNode variables = variablesJson == null ? null : MAPPER.readTree(variablesJson);
        return MutationNodeIdExtractor.extract(mutation, variables);
    }

    private static final String CREATE_ISSUE =
            "mutation($input: CreateIssueInput!) " + "{ createIssue(input: $input) { issue { id } } }";

    @Test
    void createIssue_readsRepositoryIdFromTheInputVariable() {
        var ref =
                extract(CREATE_ISSUE, "{\"input\":{\"repositoryId\":\"R_1\"}}").orElseThrow();
        assertEquals("R_1", ref.nodeId());
        assertEquals(MutationNodeIdRef.NodeType.REPOSITORY, ref.nodeType());
    }

    @Test
    void createPullRequest_readsRepositoryId() {
        var ref = extract(
                        "mutation($input: X!) { createPullRequest(input: $input) { pullRequest { url } } }",
                        "{\"input\":{\"repositoryId\":\"R_2\"}}")
                .orElseThrow();
        assertEquals("R_2", ref.nodeId());
        assertEquals(MutationNodeIdRef.NodeType.REPOSITORY, ref.nodeType());
    }

    @Test
    void updateIssue_readsId() {
        var ref = extract(
                        "mutation($input: X!) { updateIssue(input: $input) { issue { id } } }",
                        "{\"input\":{\"id\":\"I_1\"}}")
                .orElseThrow();
        assertEquals("I_1", ref.nodeId());
        assertEquals(MutationNodeIdRef.NodeType.ISSUE, ref.nodeType());
    }

    @Test
    void addComment_readsSubjectId() {
        var ref = extract(
                        "mutation($input: X!) { addComment(input: $input) { clientMutationId } }",
                        "{\"input\":{\"subjectId\":\"I_9\"}}")
                .orElseThrow();
        assertEquals("I_9", ref.nodeId());
        assertEquals(MutationNodeIdRef.NodeType.ISSUE_OR_PULL_REQUEST, ref.nodeType());
    }

    @Test
    void closePullRequest_readsPullRequestId() {
        var ref = extract(
                        "mutation($input: X!) { closePullRequest(input: $input) { clientMutationId } }",
                        "{\"input\":{\"pullRequestId\":\"PR_3\"}}")
                .orElseThrow();
        assertEquals("PR_3", ref.nodeId());
        assertEquals(MutationNodeIdRef.NodeType.PULL_REQUEST, ref.nodeType());
    }

    /**
     * A fork pull request carries both repositories: {@code repositoryId} is the base, the one authorization must run
     * against, and {@code headRepositoryId} is the fork the contributor already owns.
     */
    @Test
    void createPullRequest_fromAFork_extractsTheBaseNotTheHead() {
        var ref = extract(
                        "mutation($input: X!) { createPullRequest(input: $input) { pullRequest { url } } }",
                        "{\"input\":{\"repositoryId\":\"R_base\",\"headRepositoryId\":\"R_fork\","
                                + "\"headRefName\":\"contributor:feature\"}}")
                .orElseThrow();
        assertEquals("R_base", ref.nodeId(), "the base repository is the authorization target");
    }

    /**
     * An inline input object is a shape no supported CLI sends. Refusing it leaves the variable as the only place the
     * target can be, so what is authorized is what the upstream receives.
     */
    @Test
    void inlineInputObject_isRefused() {
        assertTrue(extract("mutation { createIssue(input: {repositoryId: \"R_inline\"}) { issue { id } } }", null)
                .isEmpty());
    }

    /** The decoupling attack the shape rules exist to stop: a permitted ID in variables, a different one executed. */
    @Test
    void inlineIdBesideADecoyVariable_isRefused() {
        assertTrue(extract(
                        "mutation { createIssue(input: {repositoryId: \"R_forbidden\"}) { issue { id } } }",
                        "{\"input\":{\"repositoryId\":\"R_allowed\"}}")
                .isEmpty());
    }

    /** The same attack by a second variable, refused a step earlier: $input is declared but never referenced. */
    @Test
    void decoyVariableAlongsideTheRealOne_isRefusedByTheParser() {
        assertThrows(
                GraphQlParseException.class,
                () -> extract(
                        "mutation($input: X!, $real: X!) { createIssue(input: $real) { issue { id } } }",
                        "{\"input\":{\"repositoryId\":\"R_allowed\"},\"real\":{\"repositoryId\":\"R_forbidden\"}}"));
    }

    /** Every captured request passes the input as {@code $input}; another name is a shape fogwall does not serve. */
    @Test
    void inputPassedUnderAnotherVariableName_isRefused() {
        assertTrue(extract(
                        "mutation($real: X!) { createIssue(input: $real) { issue { id } } }",
                        "{\"real\":{\"repositoryId\":\"R_executed\"}}")
                .isEmpty());
    }

    @Test
    void referencedVariableNeverSupplied_isEmpty() {
        assertTrue(extract("mutation($input: X!) { createIssue(input: $input) { issue { id } } }", "{}")
                .isEmpty());
    }

    @Test
    void missingVariables_isEmpty() {
        assertTrue(extract(CREATE_ISSUE, null).isEmpty());
    }

    @Test
    void wrongInputMemberForTheMutation_isEmpty() {
        assertTrue(extract(CREATE_ISSUE, "{\"input\":{\"id\":\"I_1\"}}").isEmpty());
    }

    @Test
    void nonStringId_isEmpty() {
        assertTrue(extract(CREATE_ISSUE, "{\"input\":{\"repositoryId\":123}}").isEmpty());
    }

    /**
     * The attribute follow-ups name their target through a capability interface rather than a concrete type, so the ID
     * key differs again per mutation — the reason the extractor keeps a table instead of looking for one field.
     */
    @Test
    void replaceActorsForAssignable_readsAssignableId() {
        var ref = extract(
                        "mutation($input: X!) { replaceActorsForAssignable(input: $input) { __typename } }",
                        "{\"input\":{\"assignableId\":\"I_5\",\"actorLogins\":[\"someone\"]}}")
                .orElseThrow();
        assertEquals("I_5", ref.nodeId());
        assertEquals(MutationNodeIdRef.NodeType.ISSUE_OR_PULL_REQUEST, ref.nodeType());
    }

    @Test
    void addLabelsToLabelable_readsLabelableId() {
        var ref = extract(
                        "mutation($input: X!) { addLabelsToLabelable(input: $input) { __typename } }",
                        "{\"input\":{\"labelableId\":\"PR_6\",\"labelIds\":[\"LA_1\"]}}")
                .orElseThrow();
        assertEquals("PR_6", ref.nodeId());
        assertEquals(MutationNodeIdRef.NodeType.ISSUE_OR_PULL_REQUEST, ref.nodeType());
    }

    @Test
    void removeLabelsFromLabelable_readsLabelableId() {
        var ref = extract(
                        "mutation($input: X!) { removeLabelsFromLabelable(input: $input) { __typename } }",
                        "{\"input\":{\"labelableId\":\"I_7\",\"labelIds\":[\"LA_1\"]}}")
                .orElseThrow();
        assertEquals("I_7", ref.nodeId());
        assertEquals(MutationNodeIdRef.NodeType.ISSUE_OR_PULL_REQUEST, ref.nodeType());
    }

    @Test
    void requestReviewsByLogin_readsPullRequestId() {
        var ref = extract(
                        "mutation($input: X!) { requestReviewsByLogin(input: $input) { clientMutationId } }",
                        "{\"input\":{\"pullRequestId\":\"PR_8\",\"userLogins\":[\"reviewer\"],\"union\":true}}")
                .orElseThrow();
        assertEquals("PR_8", ref.nodeId());
        assertEquals(MutationNodeIdRef.NodeType.PULL_REQUEST, ref.nodeType());
    }

    @Test
    void unknownMutation_isEmpty() {
        assertTrue(extract(
                        "mutation($input: X!) { deleteRef(input: $input) { clientMutationId } }",
                        "{\"input\":{\"repositoryId\":\"R_1\"}}")
                .isEmpty());
    }

    @Test
    void missingInputArgument_isEmpty() {
        assertTrue(extract("mutation { createIssue { issue { id } } }", null).isEmpty());
    }
}
