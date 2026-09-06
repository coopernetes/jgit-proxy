package com.rbc.fogwall.scmapi;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the {@code target_project_id} that {@code glab mr create} carries in its request body.
 *
 * <p>GitLab is the one dialect where the URL does not name the repository being written to. {@code mr create} posts to
 * the <b>source</b> project and names the upstream only here, as a numeric ID (verified against a real fork MR — see
 * docs/internals/SCM_API_PROXY.md). Authorizing on the URL alone therefore checks the fork, which the contributor owns
 * outright, instead of the upstream the merge request is opened on.
 *
 * <p>Absent is a normal case, not an error: a same-project merge request has no separate target, and the URL is then
 * the correct authorization subject. Present-but-unusable is different and must not fall back to the URL — see
 * {@link #targetProjectId}.
 */
public final class GitLabTargetProject {

    private static final JsonMapper MAPPER = new JsonMapper();
    private static final String FIELD = "target_project_id";

    private GitLabTargetProject() {}

    /** The outcome of looking for a target project in a request body. */
    public sealed interface Result {
        /** No {@code target_project_id} present — authorize on the project named in the URL. */
        record Absent() implements Result {}

        /** A usable project ID; the merge request targets this project, whatever the URL says. */
        record Present(String projectId) implements Result {}

        /**
         * The field is there but cannot be read as a project ID — unparseable body, or a non-numeric value. Never
         * treated as {@link Absent}: the field's presence says the URL is not the target, so falling back to the URL
         * would authorize the wrong repository precisely when fogwall has least idea what the right one is.
         */
        record Unusable(String reason) implements Result {}
    }

    /**
     * Extracts the target project from a {@code glab} merge-request body. The value must be a JSON number with no
     * fractional part, which is what GitLab documents and {@code glab} sends; a quoted number, a float, or any other
     * shape is {@link Result.Unusable} rather than coerced, since guessing at an authorization subject is the failure
     * this exists to prevent. {@link Result.Present} carries it as text because its only use is a URL path segment.
     */
    public static Result targetProjectId(byte[] body) {
        if (body == null || body.length == 0) {
            return new Result.Absent();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            return new Result.Unusable("request body is not valid JSON");
        }
        if (root == null || !root.isObject() || !root.has(FIELD)) {
            return new Result.Absent();
        }
        JsonNode value = root.get(FIELD);
        if (value.isNull()) {
            return new Result.Absent();
        }
        if (!value.isIntegralNumber()) {
            return new Result.Unusable(FIELD + " is not a numeric project ID");
        }
        return new Result.Present(value.asString());
    }
}
