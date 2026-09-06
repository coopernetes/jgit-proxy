package com.rbc.fogwall.scmapi;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ScmApiGraphQlRequestParserTest {

    @Test
    void parsesQueryVariablesAndOperationName() {
        var body = """
                {"query":"mutation IssueCreate($input: CreateIssueInput!) { createIssue(input: $input) { issue { id } } }",
                 "variables":{"input":{"repositoryId":"R_kgD"}},
                 "operationName":"IssueCreate"}
                """;
        var parsed = ScmApiGraphQlRequestParser.parse(body.getBytes(StandardCharsets.UTF_8));
        assertTrue(parsed.query().contains("createIssue"));
        assertEquals("IssueCreate", parsed.operationName());
        assertEquals(
                "R_kgD", parsed.variables().get("input").get("repositoryId").asString());
    }

    @Test
    void variablesAndOperationNameAreOptional() {
        var body = """
                {"query":"query { viewer { login } }"}
                """;
        var parsed = ScmApiGraphQlRequestParser.parse(body.getBytes(StandardCharsets.UTF_8));
        assertNull(parsed.variables());
        assertNull(parsed.operationName());
    }

    @Test
    void malformedJson_throwsParseException() {
        byte[] body = "not json at all {".getBytes(StandardCharsets.UTF_8);
        assertThrows(GraphQlParseException.class, () -> ScmApiGraphQlRequestParser.parse(body));
    }

    @Test
    void missingQueryField_throwsParseException() {
        byte[] body = "{\"variables\":{}}".getBytes(StandardCharsets.UTF_8);
        assertThrows(GraphQlParseException.class, () -> ScmApiGraphQlRequestParser.parse(body));
    }

    @Test
    void nonTextualQueryField_throwsParseException() {
        byte[] body = "{\"query\":123}".getBytes(StandardCharsets.UTF_8);
        assertThrows(GraphQlParseException.class, () -> ScmApiGraphQlRequestParser.parse(body));
    }

    @Test
    void emptyBody_throwsParseException() {
        byte[] body = new byte[0];
        assertThrows(GraphQlParseException.class, () -> ScmApiGraphQlRequestParser.parse(body));
    }
}
