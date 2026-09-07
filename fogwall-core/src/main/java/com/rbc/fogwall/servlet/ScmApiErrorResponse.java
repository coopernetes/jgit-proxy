package com.rbc.fogwall.servlet;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON body fogwall returns when it refuses an SCM API request.
 *
 * <p>Carries the same text under two names. {@code error} is fogwall's own; {@code message} is the field the CLIs
 * already know how to render, because it is what the providers' REST APIs use. Without it {@code gh} prints a bare
 * {@code HTTP 403} and drops the explanation, so a developer refused by a policy cannot tell which one, or that a
 * policy was involved at all. A refusal that cannot say why is not much of a control.
 */
public final class ScmApiErrorResponse {

    private static final JsonMapper MAPPER = new JsonMapper();

    private ScmApiErrorResponse() {}

    /**
     * Writes {@code status} and the reason as JSON.
     *
     * <p>Does nothing if the response is already committed. Every refusal today happens in a filter that stops the
     * chain before anything is written, so this cannot currently fire — but the forwarders write the status and body
     * before setting the request context's own status, and a later failure on that path would otherwise reach here with
     * headers already sent. Setting a status on a committed response is silently ignored, so the alternative is a body
     * appended to a successful reply, which is worse than no refusal text at all.
     */
    public static void write(HttpServletResponse response, int status, String reason) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType("application/json");
        response.getOutputStream().write(body(reason));
    }

    /**
     * The encoded body.
     *
     * <p>Serialised rather than concatenated: a reason can carry a quote, a backslash or a newline — a blocked pattern
     * is operator-authored and a scanner's finding quotes what it matched — and hand-built JSON turns any of those into
     * a body the client cannot parse.
     */
    static byte[] body(String reason) {
        String text = reason == null ? "" : reason;
        return MAPPER.writeValueAsString(Map.of("error", text, "message", text)).getBytes(StandardCharsets.UTF_8);
    }
}
