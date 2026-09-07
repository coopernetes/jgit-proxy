package com.rbc.fogwall.scmapi;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The caller-supplied prose carried by a proposal mutation — a pull/merge request title and description, or a comment
 * body.
 *
 * <p>A secret pasted into a pull request description reaches the upstream untouched unless this path inspects it, so
 * these fields are extracted and run through the same content rules a push gets.
 *
 * <p>Extraction is per dialect because only the field names differ: GitHub nests them under the GraphQL mutation's
 * {@code input}, GitLab calls a description {@code description}, Forgejo calls it {@code body}.
 */
public record ProposalContent(String field, String text) {

    /**
     * GitHub's GraphQL mutations carry their prose under {@code variables.input}, so extraction starts from the request
     * body and descends — the argument is the whole parsed request, exactly as for the REST dialects, because a
     * mismatch there is invisible: it yields no content to inspect and the request forwards untouched.
     */
    public static List<ProposalContent> fromGraphQlBody(JsonNode requestBody) {
        if (requestBody == null || !requestBody.isObject()) {
            return List.of();
        }
        JsonNode variables = requestBody.get("variables");
        return variables == null ? List.of() : from(variables.get("input"), "title", "body");
    }

    /** GitLab: {@code description} on issues and merge requests, {@code body} on notes. */
    public static List<ProposalContent> fromGitLabBody(JsonNode body) {
        return from(body, "title", "description", "body");
    }

    /** Forgejo: {@code body} for both descriptions and comments. */
    public static List<ProposalContent> fromForgejoBody(JsonNode body) {
        return from(body, "title", "body");
    }

    private static List<ProposalContent> from(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) {
            return List.of();
        }
        List<ProposalContent> content = new ArrayList<>();
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isString() && !value.asString().isBlank()) {
                content.add(new ProposalContent(field, value.asString()));
            }
        }
        return content;
    }
}
