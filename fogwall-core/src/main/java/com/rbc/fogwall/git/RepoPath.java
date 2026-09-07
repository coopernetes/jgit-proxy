package com.rbc.fogwall.git;

import java.util.List;
import java.util.Optional;

/**
 * The owner, name and slug a git request refers to, derived from its repository path.
 *
 * <p>The repository name is the last path segment and the owner is everything before it, so a GitLab subgroup project
 * {@code /group/subgroup/project} keeps its whole namespace as the owner instead of being read as the subgroup itself.
 * Splitting at the last separator rather than the first is the same rule {@code GitLabRestAllowlist} already applies on
 * the SCM API path.
 *
 * <p>Every git path derives its owner, name and slug here — both proxy modes and both of server mode's transports — so
 * that a URL rule and a permission check evaluated for one request always match against the same strings. A path this
 * class cannot parse yields an empty {@link Optional}, and callers reject rather than proceeding on a partial reading
 * of it.
 */
public record RepoPath(String owner, String name, String slug) {

    /**
     * The git smart-HTTP service paths that may follow the repository path. These are the only three operations fogwall
     * serves (see {@code FogwallFilter.determineOperation}), so the repository path is whatever precedes one of them.
     */
    private static final List<String> SERVICE_SUFFIXES = List.of("/info/refs", "/git-upload-pack", "/git-receive-pack");

    private static final String DOT_GIT = ".git";

    /**
     * Parses a repository path into its owner, name and slug.
     *
     * <p>Accepts a path with or without a leading {@code /}, with or without a trailing {@code .git}, and with or
     * without a trailing git service path such as {@code /info/refs} — so both a servlet {@code pathInfo} and an
     * already-normalised slug parse to the same result.
     *
     * @param path the repository path, e.g. {@code /group/subgroup/project.git/git-receive-pack}
     * @return the parsed reference, or empty if the path does not name at least two segments or any segment is not a
     *     valid path segment per {@link RepoSlugValidator}
     */
    public static Optional<RepoPath> parse(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }

        String repoPath = stripServiceSuffix(path);
        if (repoPath.endsWith(DOT_GIT)) {
            repoPath = repoPath.substring(0, repoPath.length() - DOT_GIT.length());
        }
        if (repoPath.startsWith("/")) {
            repoPath = repoPath.substring(1);
        }

        // The owner must be non-empty, so a single-segment path (and a path whose only separator is the one this
        // method just stripped from the front) does not name a repository.
        int lastSeparator = repoPath.lastIndexOf('/');
        if (lastSeparator <= 0 || lastSeparator == repoPath.length() - 1) {
            return Optional.empty();
        }

        String owner = repoPath.substring(0, lastSeparator);
        String name = repoPath.substring(lastSeparator + 1);

        // Validate every segment, not just the first and last: a nested owner is several segments, and traversal in
        // any one of them must not survive to an upstream URL or a cache key.
        for (String segment : owner.split("/", -1)) {
            if (!RepoSlugValidator.isValidSegment(segment)) {
                return Optional.empty();
            }
        }
        if (!RepoSlugValidator.isValidSegment(name)) {
            return Optional.empty();
        }

        return Optional.of(new RepoPath(owner, name, "/" + owner + "/" + name));
    }

    /**
     * Removes one trailing git service path, if present. Only one is removed, so a repository genuinely named
     * {@code git-receive-pack} or a project named {@code info} inside a subgroup still parses correctly.
     */
    private static String stripServiceSuffix(String path) {
        for (String suffix : SERVICE_SUFFIXES) {
            if (path.endsWith(suffix)) {
                return path.substring(0, path.length() - suffix.length());
            }
        }
        return path;
    }
}
