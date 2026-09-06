package com.rbc.fogwall.scmapi;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fail-closed allowlist of the mutating REST v1 calls made by {@code fj} and {@code tea} — see
 * docs/internals/SCM_API_PROXY.md's Forgejo section. The repository is addressed directly in the URL as two plain
 * segments ({@code /repos/{owner}/{repo}/...}), so the authorization target is read off the matched path with no
 * opaque-ID resolution step.
 *
 * <p>One table serves both CLIs, as the union of the endpoints each uses. They reach the same operation by different
 * ones — {@code tea pr close} sends {@code PATCH /pulls/{n}}, {@code fj pr close} sends {@code PATCH /issues/{n}} — so
 * allowlisting either form alone breaks the other CLI. {@code User-Agent} does not select between them: it is
 * caller-chosen, so the reachable surface is this union regardless.
 *
 * <p>Hardcoded rather than config-driven: this is the security boundary. Scoped to proposing a change — issue and PR
 * create, update, close and comment, plus the label and assignee endpoints an edit reaches. Review, merge,
 * tracked-time, dependency, blocking and release endpoints are absent and therefore denied.
 */
public final class ForgejoRestAllowlist {

    private record Rule(String method, Pattern pathPattern, String operation) {}

    /** {@code {owner}/{repo}}, each its own path segment — the CLIs URL-encode the two independently. */
    private static final String REPO = "^/repos/([^/]+)/([^/]+)";

    private static final List<Rule> RULES = List.of(
            new Rule("POST", Pattern.compile(REPO + "/issues$"), "issues.create"),
            // Also carries `fj pr close` and `fj issue close` — both send {"state":"closed"} here.
            new Rule("PATCH", Pattern.compile(REPO + "/issues/\\d+$"), "issues.update"),
            // Comments on a PR use the issue path too, in both CLIs — Forgejo models a PR as an issue.
            new Rule("POST", Pattern.compile(REPO + "/issues/\\d+/comments$"), "issues.comment"),
            new Rule("PATCH", Pattern.compile(REPO + "/issues/comments/\\d+$"), "issues.comment.update"),
            new Rule("POST", Pattern.compile(REPO + "/pulls$"), "pulls.create"),
            // Also carries `tea pr close`: tea sends a full-object PATCH, so close and edit are the same request
            // shape on the wire and cannot be told apart here. Granularity is method+path, never intent.
            new Rule("PATCH", Pattern.compile(REPO + "/pulls/\\d+$"), "pulls.update"),
            // Attribute follow-ups. tea inlines labels and assignees on create, but `issue edit` reaches them
            // through their own endpoints, so without these an edit leaves the issue updated and the attribute
            // unchanged. The PR forms are absent because Forgejo models a PR as an issue and tea addresses both here.
            new Rule("POST", Pattern.compile(REPO + "/issues/\\d+/labels$"), "issues.labels.add"),
            new Rule("POST", Pattern.compile(REPO + "/issues/\\d+/assignees$"), "issues.assignees.add"),
            new Rule("DELETE", Pattern.compile(REPO + "/issues/\\d+/assignees$"), "issues.assignees.remove"));

    private ForgejoRestAllowlist() {}

    /**
     * Matches an incoming {@code method}/{@code path} against the allowlist, where {@code path} is the raw,
     * still-URL-encoded request sub-path below the dialect's {@code /api/v1} mount point (e.g.
     * {@code /repos/acme/widgets/issues}). Returns empty when nothing matches — the caller must fail closed.
     */
    public static Optional<ScmApiRestMatch> match(String method, String path) {
        if (method == null || path == null) return Optional.empty();
        for (Rule rule : RULES) {
            if (!rule.method().equalsIgnoreCase(method)) continue;
            Matcher matcher = rule.pathPattern().matcher(path);
            if (!matcher.matches()) continue;
            String owner = decodeSegment(matcher.group(1));
            String repo = decodeSegment(matcher.group(2));
            if (owner.isEmpty() || repo.isEmpty()) continue;
            return Optional.of(new ScmApiRestMatch(rule.operation(), new OwnerRepo(owner, repo)));
        }
        return Optional.empty();
    }

    private static String decodeSegment(String encoded) {
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }
}
