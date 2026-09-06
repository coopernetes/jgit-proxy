package com.rbc.fogwall.servlet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ScmApiErrorResponseTest {

    private static final JsonMapper MAPPER = new JsonMapper();

    private static JsonNode parse(String reason) {
        return MAPPER.readTree(new String(ScmApiErrorResponse.body(reason), StandardCharsets.UTF_8));
    }

    @Test
    void carriesTheReasonUnderBothNames() {
        var body = parse("Content rejected: possible Social Insurance Number (CA)");
        assertEquals(
                "Content rejected: possible Social Insurance Number (CA)",
                body.get("error").asString());
        assertEquals(
                body.get("error").asString(),
                body.get("message").asString(),
                "gh renders 'message'; dropping it leaves the caller a bare 403");
    }

    @Test
    void survivesAReasonCarryingJsonPunctuation() {
        String reason = "blocked pattern: \"https?://\\S+\" matched\nline 2\ttabbed";
        assertEquals(reason, parse(reason).get("message").asString(), "quotes, backslashes and newlines round-trip");
    }

    /**
     * A refusal appended to a reply already on the wire is not a refusal — the status is silently dropped and the
     * caller gets a successful response with error JSON stuck on the end.
     */
    @Test
    void writesNothingToACommittedResponse() throws Exception {
        var written = new ByteArrayOutputStream();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.isCommitted()).thenReturn(true);

        ScmApiErrorResponse.write(response, HttpServletResponse.SC_FORBIDDEN, "too late");

        assertEquals(0, written.size());
        verify(response, never()).setStatus(anyInt());
        verify(response, never()).getOutputStream();
    }

    @Test
    void treatsAMissingReasonAsEmptyRatherThanNull() {
        var body = parse(null);
        assertEquals("", body.get("error").asString());
        assertEquals("", body.get("message").asString());
    }
}
