package com.rbc.fogwall.servlet;

import java.util.List;
import java.util.Locale;

/**
 * Structural check on the raw sub-path of a REST dialect request, applied before the allowlist and again before the
 * request is forwarded.
 *
 * <p>Two things are rejected outright on every dialect:
 *
 * <ul>
 *   <li><b>Traversal segments</b> — a {@code .} or {@code ..} segment. Jetty resolves these while parsing, so the path
 *       reaching a filter is already canonical; checking again keeps the guarantee attached to the path rather than to
 *       container behaviour.
 *   <li><b>Encoded path separators</b> — {@code %2F} and {@code %5C}, in either case — everywhere except the one place
 *       a dialect needs them.
 * </ul>
 *
 * <p>GitLab addresses a project as a single URL-encoded {@code owner/repo} segment, so
 * {@code /projects/acme%2Fwidgets/issues} is ordinary traffic, confined to the segment after {@code projects}. Forgejo
 * encodes a repository-relative file path into one segment of its blob endpoints, which {@code fj} reads before
 * creating a pull request, confined to the path portion. GitHub needs no exception: one fixed GraphQL path.
 *
 * <p>An encoded separator is refused everywhere else, including in the segments each dialect's authorization decision
 * is read from. That confinement is the other half of the connector's relaxed {@code UriCompliance}: each place an
 * ambiguous path is accepted is a place the permission engine and the upstream could read it differently.
 */
public final class ScmApiRestPathPolicy {

    /** Where, if anywhere, an encoded path separator is legitimate for a dialect. */
    public enum EncodedSeparators {
        /** No encoded separator is ever valid. */
        REJECTED,
        /** Valid only in the segment following {@code projects} — GitLab's URL-encoded {@code owner/repo}. */
        GITLAB_PROJECT_SEGMENT,
        /**
         * Valid only in the file path of a Forgejo blob endpoint — {@code /repos/{owner}/{repo}/raw/{path}} and its
         * {@code contents}/{@code media} siblings, where the whole repository-relative path is one encoded segment.
         * {@code fj} reads {@code .forgejo%2Fpull_request_template.md} before creating a pull request, so refusing this
         * outright breaks the CLI. The owner and repo segments are still refused, which is what the authorization
         * decision reads.
         */
        FORGEJO_FILE_PATH
    }

    private static final String PROJECTS_SEGMENT = "projects";
    private static final String REPOS_SEGMENT = "repos";
    private static final List<String> FORGEJO_BLOB_SEGMENTS = List.of("raw", "contents", "media");
    private static final int FORGEJO_FILE_PATH_INDEX = 4;
    private static final String ENCODED_SLASH = "%2f";
    private static final String ENCODED_BACKSLASH = "%5c";

    private ScmApiRestPathPolicy() {}

    /**
     * Whether {@code rawSubPath} (still encoded, from {@link ScmApiRestPath#rawSubPath}) may be matched and forwarded.
     * An empty path is acceptable — it addresses the dialect root, which the allowlist then refuses on its own terms.
     */
    public static boolean isForwardable(String rawSubPath, EncodedSeparators encodedSeparators) {
        if (rawSubPath == null) return false;
        if (rawSubPath.isEmpty()) return true;
        if (!rawSubPath.startsWith("/")) return false;

        String[] segments = rawSubPath.substring(1).split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i].toLowerCase(Locale.ROOT);
            if (isTraversal(segment)) return false;
            // An encoded backslash is refused unconditionally: no dialect addresses anything with one, and some path
            // handling treats it as a separator, so the only thing permitting it could do is create a second reading.
            if (segment.contains(ENCODED_BACKSLASH)) return false;
            if (segment.contains(ENCODED_SLASH) && !isEncodedSlashAllowed(segments, i, encodedSeparators)) return false;
        }
        return true;
    }

    /**
     * A traversal segment in either literal or percent-encoded form. {@code %2e%2e} decodes to {@code ..} and is the
     * same instruction to whatever normalises the path downstream, so matching only the literal form would leave the
     * check trivially bypassable. {@code segment} is already lower-cased by the caller.
     */
    private static boolean isTraversal(String segment) {
        String decoded = segment.replace("%2e", ".");
        return decoded.equals(".") || decoded.equals("..");
    }

    private static boolean isEncodedSlashAllowed(String[] segments, int index, EncodedSeparators policy) {
        return switch (policy) {
            case REJECTED -> false;
            case GITLAB_PROJECT_SEGMENT -> index == 1 && PROJECTS_SEGMENT.equals(segments[0]);
            case FORGEJO_FILE_PATH ->
                index >= FORGEJO_FILE_PATH_INDEX
                        && segments.length > 3
                        && REPOS_SEGMENT.equals(segments[0])
                        && FORGEJO_BLOB_SEGMENTS.contains(segments[3]);
        };
    }
}
