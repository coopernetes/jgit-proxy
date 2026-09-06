package com.rbc.fogwall.scmapi;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GraphQlMutationParserTest {

    private static String field(String query, String operationName) {
        return GraphQlMutationParser.selectMutationField(query, operationName)
                .orElseThrow()
                .getName();
    }

    @Test
    void mutation_returnsSchemaFieldName() {
        assertEquals("createIssue", field("mutation { createIssue(input: {}) { issue { id } } }", null));
    }

    @Test
    void aliasedMutation_returnsSchemaFieldNotAlias() {
        assertEquals("createIssue", field("mutation { harmless: createIssue(input: {}) { issue { id } } }", null));
    }

    @Test
    void namedOperation_returnsSchemaFieldName() {
        assertEquals(
                "createPullRequest",
                field(
                        "mutation PullRequestCreate($i: X!) { createPullRequest(input: $i) { pullRequest { url } } }",
                        null));
    }

    @Test
    void query_isARead() {
        assertTrue(GraphQlMutationParser.selectMutationField("query { viewer { login } }", null)
                .isEmpty());
    }

    @Test
    void queryContainingTheWordMutation_isStillARead() {
        assertTrue(GraphQlMutationParser.selectMutationField(
                        "query { search(query: \"createIssue mutation\") { codeCount } }", null)
                .isEmpty());
    }

    @Test
    void malformedDocument_throws() {
        assertThrows(
                GraphQlParseException.class, () -> GraphQlMutationParser.selectMutationField("mutation { {{{", null));
    }

    /**
     * GraphQL executes every root field of a mutation, serially. Authorizing the first and forwarding the rest would
     * let an allowlisted mutation carry an arbitrary second one.
     */
    @Test
    void mutationSelectingSeveralRootFields_isRefused() {
        var e = assertThrows(
                GraphQlParseException.class,
                () -> GraphQlMutationParser.selectMutationField(
                        "mutation { createIssue(input: {}) { issue { id } } deleteRef(input: {}) { clientMutationId } }",
                        null));
        assertTrue(e.getMessage().contains("exactly one field"), e.getMessage());
    }

    /**
     * A second operation is somewhere to put a mutation the authorized one distracts from — whichever of the two
     * {@code operationName} then picks. No supported CLI sends more than one, so the document is refused rather than
     * reasoned about.
     */
    @Test
    void severalOperations_areRefusedWhicheverIsNamed() {
        String document = "mutation A { createIssue(input: {}) { issue { id } } }"
                + " mutation B { deleteRef(input: {}) { clientMutationId } }";
        assertThrows(GraphQlParseException.class, () -> GraphQlMutationParser.selectMutationField(document, null));
        assertThrows(GraphQlParseException.class, () -> GraphQlMutationParser.selectMutationField(document, "A"));
        assertThrows(GraphQlParseException.class, () -> GraphQlMutationParser.selectMutationField(document, "B"));
    }

    @Test
    void operationNameNamingADifferentOperation_isRefused() {
        assertThrows(
                GraphQlParseException.class,
                () -> GraphQlMutationParser.selectMutationField(
                        "mutation A { createIssue(input: {}) { issue { id } } }", "B"));
    }

    @Test
    void operationNameMatchingTheSingleOperation_isAccepted() {
        assertEquals(
                "createIssue",
                field("mutation IssueCreate { createIssue(input: {}) { issue { id } } }", "IssueCreate"));
    }

    /** A root fragment would otherwise leave the selection empty, classifying a mutation as a read. */
    @Test
    void mutationHidingItsFieldBehindAFragmentSpread_isRefused() {
        assertThrows(
                GraphQlParseException.class,
                () -> GraphQlMutationParser.selectMutationField(
                        "mutation { ...M } fragment M on Mutation { deleteRef(input: {}) { clientMutationId } }",
                        null));
    }

    @Test
    void mutationHidingItsFieldBehindAnInlineFragment_isRefused() {
        assertThrows(
                GraphQlParseException.class,
                () -> GraphQlMutationParser.selectMutationField(
                        "mutation { ... on Mutation { deleteRef(input: {}) { clientMutationId } } }", null));
    }

    @Test
    void documentWithNoOperation_isRefused() {
        assertThrows(
                GraphQlParseException.class,
                () -> GraphQlMutationParser.selectMutationField("fragment M on Mutation { deleteRef { id } }", null));
    }

    @Test
    void subscription_isRefused() {
        assertThrows(
                GraphQlParseException.class,
                () -> GraphQlMutationParser.selectMutationField("subscription { events { id } }", null));
    }
}
