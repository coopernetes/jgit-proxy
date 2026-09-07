package com.rbc.fogwall.git;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Case folding for the two places that compare a repository path against an administrator's pattern:
 * {@code UrlRuleEvaluator} for URL rules and {@code RepoPermissionService} for permission grants.
 *
 * <p>Every supported provider resolves {@code owner/repo} case-insensitively, so {@code /acme/widgets} and
 * {@code /Acme/Widgets} are the same repository upstream. Comparing them case-sensitively let a differently-cased path
 * walk past a {@code DENY} rule into a broader allow ordered below it.
 *
 * <p>Folding also pins {@code GLOB}, which otherwise inherits the host filesystem's case sensitivity through
 * {@link java.nio.file.FileSystem#getPathMatcher} — case-sensitive on Linux, not on macOS.
 *
 * <p>Lives beside {@link RepoPath} because the two answer halves of the same question: {@code RepoPath} decides which
 * part of a request path a rule is compared against, this decides how that comparison is made. Both sites route through
 * here so the two can never disagree on either half.
 *
 * <p>{@link RepoSlugValidator} restricts path segments to {@code [A-Za-z0-9._-]}, so ASCII folding under
 * {@link Locale#ROOT} is exact and does not vary with the JVM's default locale.
 */
public final class RepoPathMatching {

    /** Flags every repository-path pattern is compiled with. */
    public static final int REGEX_FLAGS = Pattern.CASE_INSENSITIVE;

    private RepoPathMatching() {}

    /** Folds a pattern or candidate to the form comparisons are made in. */
    public static String fold(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    /** Exact equality, ignoring case. */
    public static boolean literalMatches(String pattern, String value) {
        return pattern.equalsIgnoreCase(value);
    }

    /** Glob match, ignoring case regardless of what the host filesystem would do on its own. */
    public static boolean globMatches(String pattern, String value) {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + fold(pattern));
        return matcher.matches(Paths.get(fold(value)));
    }
}
