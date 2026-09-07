package com.rbc.fogwall.scmapi;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The two readings of a proposal request body that content inspection needs.
 *
 * <p>Scanning either one alone leaves a gap. The <b>raw</b> bytes cover every field, including ones no extractor knows
 * to look for — a dialect gaining a new prose field would otherwise go silently uninspected. The <b>decoded</b>
 * rendering, every JSON string value in the document, defeats escaping: {@code "ghp_\\u0041BC…"} matches nothing as raw
 * text but is plainly a token once decoded.
 *
 * <p>Both are handed to the scanner together, as one document, so this costs a single gitleaks invocation for the whole
 * request rather than one per field.
 */
public record ProposalPayload(String raw, String decoded) {

    /** Reads both forms from the request body; {@code parsed} may be {@code null} when the body would not parse. */
    public static ProposalPayload of(byte[] body, JsonNode parsed) {
        return of(body, parsed, null, List.of());
    }

    /**
     * As {@link #of(byte[], JsonNode)}, plus values a dialect can only expose by parsing further. GraphQL needs this:
     * its query is a JSON string, so decoding the transport leaves GraphQL's own literal escaping untouched, and
     * arguments inlined in the query text never appear as JSON values at all.
     */
    public static ProposalPayload of(byte[] body, JsonNode parsed, List<String> additionalReadings) {
        return of(body, parsed, null, additionalReadings);
    }

    /**
     * As above, including the request's query string.
     *
     * <p>Not an edge case: GitLab and Forgejo accept the same parameters in the query string as in the body, so a
     * proposal can carry its entire description there and leave the body empty. Inspecting only the body would let that
     * through untouched while the forwarder relayed it verbatim.
     */
    public static ProposalPayload of(
            byte[] body, JsonNode parsed, String queryString, List<String> additionalReadings) {
        var raw = new StringBuilder(body == null ? "" : new String(body, StandardCharsets.UTF_8));
        if (queryString != null && !queryString.isEmpty()) {
            raw.append('\n').append(queryString);
        }
        var values = new ArrayList<String>();
        collectValues(parsed, values);
        values.addAll(decodeQueryString(queryString));
        values.addAll(additionalReadings);
        return new ProposalPayload(raw.toString(), String.join("\n", values));
    }

    /** Percent-decoded names and values, so an encoded secret is as visible here as it is in a decoded JSON body. */
    private static List<String> decodeQueryString(String queryString) {
        if (queryString == null || queryString.isEmpty()) {
            return List.of();
        }
        var values = new ArrayList<String>();
        for (String pair : queryString.split("&")) {
            for (String part : pair.split("=", 2)) {
                if (!part.isEmpty()) {
                    values.add(URLDecoder.decode(part, StandardCharsets.UTF_8));
                }
            }
        }
        return values;
    }

    /** One document carrying both readings, for a single pass of an external scanner. */
    public String combined() {
        return decoded.isEmpty() ? raw : raw + "\n" + decoded;
    }

    /** Every key and every scalar in the tree, whatever its JSON type. */
    private static void collectValues(JsonNode node, List<String> into) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                into.add(entry.getKey());
                collectValues(entry.getValue(), into);
            });
        } else if (node.isArray()) {
            node.values().forEach(child -> collectValues(child, into));
        } else {
            into.add(node.asString());
        }
    }
}
