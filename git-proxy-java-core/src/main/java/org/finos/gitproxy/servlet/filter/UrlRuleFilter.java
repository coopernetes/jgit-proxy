package org.finos.gitproxy.servlet.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.db.model.AccessRule;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.provider.GitProxyProvider;

/**
 * Holds a compiled allow/deny rule from configuration. Used by {@link UrlRuleEvaluator} to evaluate rules in both
 * proxy-mode and store-and-forward mode.
 *
 * <p>Each entry in the pattern list may be:
 *
 * <ul>
 *   <li><b>Literal</b> — exact string match.
 *   <li><b>Glob</b> — any entry containing {@code *}, {@code ?}, or {@code [} is treated as a glob pattern as defined
 *       by {@link java.nio.file.FileSystem#getPathMatcher}. For slug patterns spanning owner and name (e.g.
 *       {@code owner-prefix&#47;*}), use {@code /} as the separator.
 *   <li><b>Regex</b> — entries prefixed with {@code regex:} are compiled as Java regular expressions (e.g.
 *       {@code regex:^myorg/.*}).
 * </ul>
 *
 * <p>Evaluation order: exact match → glob → regex. The first match wins.
 */
@Slf4j
public class UrlRuleFilter extends AbstractProviderAwareGitProxyFilter {

    public enum Target {
        OWNER,
        NAME,
        SLUG
    }

    private static final String REGEX_PREFIX = "regex:";

    // URL rule filters must be in the authorization range 50-199
    private static final int MIN_ORDER = 50;
    private static final int MAX_ORDER = 199;

    @Getter
    private final AccessRule.Access access;

    private final List<String> entries;
    private final Target target;
    private final Map<String, PathMatcher> globPatterns;
    private final Map<String, Pattern> regexPatterns;

    public UrlRuleFilter(int order, GitProxyProvider provider, List<String> entries, Target target) {
        this(order, DEFAULT_OPERATIONS, provider, entries, target, AccessRule.Access.ALLOW);
    }

    public UrlRuleFilter(
            int order, GitProxyProvider provider, List<String> entries, Target target, AccessRule.Access access) {
        this(order, DEFAULT_OPERATIONS, provider, entries, target, access);
    }

    public UrlRuleFilter(
            int order,
            Set<HttpOperation> appliedOperations,
            GitProxyProvider provider,
            List<String> entries,
            Target target) {
        this(order, appliedOperations, provider, entries, target, AccessRule.Access.ALLOW);
    }

    public UrlRuleFilter(
            int order,
            Set<HttpOperation> appliedOperations,
            GitProxyProvider provider,
            List<String> entries,
            Target target,
            AccessRule.Access access) {
        super(validateOrder(order), appliedOperations, provider);
        this.access = access;
        this.entries = entries;
        this.target = target;
        this.globPatterns = compileGlobPatterns(entries);
        this.regexPatterns = compileRegexPatterns(entries);
    }

    private static Map<String, PathMatcher> compileGlobPatterns(List<String> entries) {
        Map<String, PathMatcher> patterns = new LinkedHashMap<>();
        for (String entry : entries) {
            if (isGlobPattern(entry)) {
                patterns.put(entry, FileSystems.getDefault().getPathMatcher("glob:" + entry));
                log.debug("Compiled glob pattern: {}", entry);
            }
        }
        return Collections.unmodifiableMap(patterns);
    }

    private static Map<String, Pattern> compileRegexPatterns(List<String> entries) {
        Map<String, Pattern> patterns = new LinkedHashMap<>();
        for (String entry : entries) {
            if (isRegexPattern(entry)) {
                String regex = entry.substring(REGEX_PREFIX.length());
                patterns.put(entry, Pattern.compile(regex));
                log.debug("Compiled regex pattern: {}", regex);
            }
        }
        return Collections.unmodifiableMap(patterns);
    }

    private static boolean isGlobPattern(String s) {
        return !isRegexPattern(s) && (s.contains("*") || s.contains("?") || s.contains("["));
    }

    private static boolean isRegexPattern(String s) {
        return s.startsWith(REGEX_PREFIX);
    }

    private static int validateOrder(int order) {
        if (order < MIN_ORDER || order > MAX_ORDER) {
            throw new IllegalArgumentException(String.format(
                    "UrlRuleFilter order must be in the authorization range %d-%d (inclusive), but was %d",
                    MIN_ORDER, MAX_ORDER, order));
        }
        return order;
    }

    @Override
    public String beanName() {
        return String.join(
                "-",
                provider.getName(),
                target.name(),
                Integer.toString(this.order),
                this.getClass().getSimpleName());
    }

    private boolean matchesGlob(String pattern, String value) {
        PathMatcher matcher = globPatterns.get(pattern);
        if (matcher == null) return false;
        return matcher.matches(Paths.get(value));
    }

    private boolean matchesRegex(String pattern, String value) {
        Pattern compiled = regexPatterns.get(pattern);
        if (compiled == null) return false;
        return compiled.matcher(value).matches();
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response) {
        // no-op — UrlRuleEvaluator drives rule evaluation; this filter is a rule spec holder
    }

    public boolean appliesTo(HttpOperation operation) {
        return applicableOperations.contains(operation);
    }

    /**
     * Returns true if this rule matches the given repo reference. Used by {@link UrlRuleEvaluator} which operates
     * without an {@link HttpServletRequest}.
     *
     * <p>Leading {@code /} characters are stripped from both the stored entry and the supplied value before comparison
     * so that {@code /owner/repo} and {@code owner/repo} are treated identically.
     */
    public boolean matchesRepo(String slug, String owner, String name) {
        String value =
                switch (target) {
                    case SLUG -> slug;
                    case OWNER -> owner;
                    case NAME -> name;
                };
        if (value == null) return false;
        String normalised = stripLeadingSlash(value);
        return entries.stream().anyMatch(e -> {
            // Literal: strip leading slashes from both sides so /owner/repo and owner/repo are identical.
            // Glob: use the original entry key (compiled patterns are keyed by the unstripped entry) and
            //   the original value so that slash-anchored patterns like /*/public-* match correctly.
            // Regex: use the original entry and raw value so users can write patterns relative to the
            //   full slug form (e.g. regex:/myorg/.*).
            String en = stripLeadingSlash(e);
            return en.equals(normalised) || matchesGlob(e, value) || matchesRegex(e, value);
        });
    }

    private static String stripLeadingSlash(String s) {
        return (s != null && s.startsWith("/")) ? s.substring(1) : s;
    }

    @Override
    public String toString() {
        return "UrlRuleFilter{" + "access=" + access + ", entries=" + entries + ", target=" + target + '}';
    }
}
