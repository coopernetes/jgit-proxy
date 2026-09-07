package com.rbc.fogwall.scmapi;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fail-closed allowlist of {@code glab}'s mutating REST v4 calls, verified from live traffic — see
 * docs/internals/SCM_API_PROXY.md's GitLab section. Unlike GitHub's GraphQL dialect, GitLab addresses its target
 * directly in the URL as a URL-encoded {@code owner/repo} path segment, so there is no opaque-ID resolution step: the
 * authorization target is read straight off the matched path.
 *
 * <p>Hardcoded rather than config-driven: this is the security boundary.
 */
public final class GitLabRestAllowlist {

    private record Rule(String method, Pattern pathPattern, String operation) {}

    private static final List<Rule> RULES = List.of(
            new Rule("POST", Pattern.compile("^/projects/([^/]+)/issues$"), "issues.create"),
            new Rule("PUT", Pattern.compile("^/projects/([^/]+)/issues/\\d+$"), "issues.update"),
            new Rule("POST", Pattern.compile("^/projects/([^/]+)/issues/\\d+/notes$"), "issues.note"),
            new Rule("POST", Pattern.compile("^/projects/([^/]+)/merge_requests$"), "merge_requests.create"),
            new Rule("PUT", Pattern.compile("^/projects/([^/]+)/merge_requests/\\d+$"), "merge_requests.update"),
            new Rule("POST", Pattern.compile("^/projects/([^/]+)/merge_requests/\\d+/notes$"), "merge_requests.note"));

    private GitLabRestAllowlist() {}

    /**
     * Matches an incoming {@code method}/{@code path} (path is the request's sub-path under the SCM API mount point,
     * e.g. {@code /projects/acme%2Fwidgets/issues}) against the allowlist. Returns empty when nothing matches (the
     * caller must fail closed) or when the project segment is a bare numeric project ID rather than a URL-encoded
     * {@code owner/repo} path — that addressing form was not observed in verified captures and is not yet supported.
     */
    public static Optional<ScmApiRestMatch> match(String method, String path) {
        if (method == null || path == null) return Optional.empty();
        for (Rule rule : RULES) {
            if (!rule.method().equalsIgnoreCase(method)) continue;
            Matcher matcher = rule.pathPattern().matcher(path);
            if (!matcher.matches()) continue;
            Optional<OwnerRepo> ownerRepo = decodeProjectPath(matcher.group(1));
            if (ownerRepo.isEmpty()) continue;
            return Optional.of(new ScmApiRestMatch(rule.operation(), ownerRepo.get()));
        }
        return Optional.empty();
    }

    /**
     * Splits the decoded project path into owner and name at the <b>last</b> separator, not the first. GitLab nests
     * groups, so {@code group/subgroup/project} is an ordinary project and owner holds the whole namespace — the only
     * split that lets {@code "/" + owner + "/" + name} reconstruct the path the caller addressed, which is the slug the
     * permission engine matches and the audit record keeps. Splitting on the first separator would authorize
     * {@code /group/subgroup} while forwarding to a different project. GitLab is the only dialect where this arises:
     * GitHub owners are single-segment, and Forgejo addresses owner and repo as two plain segments.
     */
    private static Optional<OwnerRepo> decodeProjectPath(String encoded) {
        String decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        if (hasTraversalSegment(decoded)) return Optional.empty();
        int idx = decoded.lastIndexOf('/');
        if (idx <= 0 || idx == decoded.length() - 1) return Optional.empty();
        return Optional.of(new OwnerRepo(decoded.substring(0, idx), decoded.substring(idx + 1)));
    }

    /**
     * Rejects a project path containing a {@code .} or {@code ..} segment. A decoded slash is legitimate here — GitLab
     * nests groups, so {@code group%2Fsubgroup%2Fproject} is an ordinary project — which means the decoded value cannot
     * simply be required to be slash-free. A traversal segment, however, is never part of a real project path, and
     * allowing one would let the string that the permission engine matches on differ from the repository the request
     * actually reaches upstream.
     */
    private static boolean hasTraversalSegment(String decoded) {
        for (String segment : decoded.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) return true;
        }
        return false;
    }
}
