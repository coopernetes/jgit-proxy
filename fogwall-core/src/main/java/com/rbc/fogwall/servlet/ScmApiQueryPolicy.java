package com.rbc.fogwall.servlet;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The query parameters a conforming SCM CLI sends on the REST dialects. Anything else is refused. GitHub's GraphQL
 * dialect does not consult this: its forwarder posts to one fixed URL, so a query string the caller sends is dropped
 * rather than relayed.
 *
 * <p>An allowlist: fogwall proxies four known clients whose wire shape is captured, so a parameter that turns up here
 * is not from a client fogwall supports.
 *
 * <p>The forwarder relays the query string upstream unchanged, and both REST providers accept a credential there —
 * GitLab tries {@code access_token} before the bearer header and falls back to {@code private_token}; Forgejo checks
 * {@code token} and {@code access_token} where query-token auth is enabled. Unfiltered, a request could present one
 * token in the header, which fogwall authenticates, authorizes and audits, and a different one in the query, which the
 * upstream acts on. {@code sudo} does the same without a second token.
 *
 * <p>A CLI release sending a new parameter is refused until it is added here: a visible failure that names the
 * parameter, recoverable by capturing the new traffic.
 */
public final class ScmApiQueryPolicy {

    /**
     * Filtering and pagination parameters observed on the CLIs' read traffic. Mutations get none: no create, edit,
     * close or comment in any dialect carries a query parameter — the body holds everything.
     */
    private static final Set<String> READ_PARAMETERS = Set.of(
            // pagination, all dialects
            "page",
            "limit",
            "per_page",
            "pagelen",
            // issue/PR filtering
            "state",
            "labels",
            "milestone",
            "milestones",
            "assignee",
            "assigned_by",
            "created_by",
            "mentioned_by",
            "author_id",
            "author_username",
            "scope",
            "search",
            "q",
            "type",
            "since",
            "before",
            "sort",
            "order_by",
            "view",
            // ref and path selection on blob reads
            "ref",
            "filepath",
            "recursive",
            // identity lookup: glab resolves every --assignee/--reviewer login to a numeric id before it can send
            // the mutation, via GET /users?username=…
            "username",
            // response-shape flags on glab's GET /projects/:path, which precedes every create
            "license",
            "with_custom_attributes");

    private ScmApiQueryPolicy() {}

    /**
     * @param mutation whether the request is a write, which permits no parameters at all
     * @return the first parameter that is not permitted, or {@code null} when every parameter is
     */
    public static String refusedParameter(String queryString, boolean mutation) {
        if (queryString == null || queryString.isEmpty()) {
            return null;
        }
        Set<String> permitted = mutation ? Set.of() : READ_PARAMETERS;
        for (String pair : queryString.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String rawName = eq < 0 ? pair : pair.substring(0, eq);
            // Percent-decoded and lower-cased before matching: %74oken and token name the same parameter upstream,
            // and a proxy should not depend on the provider's own case sensitivity.
            String name =
                    URLDecoder.decode(rawName, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
            if (!permitted.contains(name)) {
                return name;
            }
        }
        return null;
    }

    /** The permitted read parameters, for messages and tests. */
    public static List<String> readParameters() {
        return READ_PARAMETERS.stream().sorted().toList();
    }
}
