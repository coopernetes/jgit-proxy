package com.rbc.fogwall.scmapi;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ProposalPayloadTest {

    private static final JsonMapper MAPPER = new JsonMapper();

    private static ProposalPayload of(String json) {
        return ProposalPayload.of(json.getBytes(StandardCharsets.UTF_8), MAPPER.readTree(json));
    }

    /** Nothing about a JSON document says which members carry prose, so the walk takes all of them, at any depth. */
    @Test
    void collectsEveryKeyAndScalarAtAnyDepth() {
        var payload = of("""
                {"title":"t","meta":{"nested":{"deep":"buried"},"list":[{"x":"in-array"},"bare"]},"n":42,"ok":true}""");
        for (String expected : new String[] {"t", "buried", "in-array", "bare", "42", "true", "title", "deep"}) {
            assertTrue(payload.decoded().contains(expected), () -> expected + " missing from " + payload.decoded());
        }
    }

    /** The decoded reading is what defeats escaping; the raw reading is what covers unparsed bytes. */
    @Test
    void decodedRevealsWhatRawHides() {
        var payload = of("{\"body\":\"ghp\\u005fabc\"}");
        assertFalse(payload.raw().contains("ghp_abc"), "raw keeps the escape sequence");
        assertTrue(payload.decoded().contains("ghp_abc"), "decoded resolves it");
        assertTrue(payload.combined().contains("ghp_abc"));
    }

    @Test
    void survivesAnUnparseableBody() {
        var payload = ProposalPayload.of("{not json".getBytes(StandardCharsets.UTF_8), null);
        assertEquals("{not json", payload.raw());
        assertEquals("", payload.decoded());
        assertEquals("{not json", payload.combined());
    }

    /**
     * GitLab and Gitea accept the same parameters in the query string as in the body, so a proposal can carry its whole
     * description there and leave the body empty — inspecting only the body would forward it untouched.
     */
    @Test
    void includesTheQueryStringInBothReadings() {
        var payload =
                ProposalPayload.of(new byte[0], null, "title=t&description=see%20internal.corp.example.com", List.of());
        assertTrue(payload.raw().contains("description=see%20internal.corp.example.com"));
        assertTrue(
                payload.decoded().contains("see internal.corp.example.com"),
                "percent-encoding must not hide it: " + payload.decoded());
    }
}
