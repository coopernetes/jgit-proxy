package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;

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
import java.util.function.Predicate;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.db.model.AccessRule;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.provider.GitProxyProvider;

/**
 * A filter that checks a request URL against a list of patterns and marks the request as matched when any entry
 * matches. Used by {@link UrlRuleAggregateFilter} to evaluate allow/deny rules from configuration.
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

    public static final String MATCHED_BY_ATTRIBUTE = "org.finos.gitproxy.servlet.filter.UrlRuleFilter.matchedBy";
    public static final String DENIED_BY_ATTRIBUTE = "org.finos.gitproxy.servlet.filter.UrlRuleFilter.deniedBy";

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

    public boolean isMatched(Predicate<String> predicate) {
        return entries.stream().anyMatch(predicate);
    }

    public Predicate<String> createPredicate(Target target, HttpServletRequest request) {
        var details = (GitRequestDetails) request.getAttribute(GIT_REQUEST_ATTR);
        if (target == Target.OWNER) {
            String owner = details.getRepoRef().getOwner();
            return e -> e.equals(owner) || matchesGlob(e, owner) || matchesRegex(e, owner);
        }
        if (target == Target.NAME) {
            String name = details.getRepoRef().getName();
            return e -> e.equals(name) || matchesGlob(e, name) || matchesRegex(e, name);
        }
        if (target == Target.SLUG) {
            String slug = details.getRepoRef().getSlug();
            return e -> e.equals(slug) || matchesGlob(e, slug) || matchesRegex(e, slug);
        }
        throw new IllegalArgumentException("Unknown target type: " + target);
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
        // no-op — the aggregate filter drives rule evaluation
    }

    public boolean appliesTo(HttpOperation operation) {
        return applicableOperations.contains(operation);
    }

    public void applyRule(HttpServletRequest request) {
        var predicate = createPredicate(target, request);
        if (isMatched(predicate)) {
            if (access == AccessRule.Access.DENY) {
                request.setAttribute(DENIED_BY_ATTRIBUTE, this.toString());
            } else {
                request.setAttribute(MATCHED_BY_ATTRIBUTE, this.toString());
            }
        }
    }

    /**
     * Returns true if this rule matches the given repo reference. Used by
     * {@link org.finos.gitproxy.git.RepositoryUrlRuleHook} which operates inside the JGit hook chain where no
     * {@link HttpServletRequest} is available.
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
            String en = stripLeadingSlash(e);
            return en.equals(normalised) || matchesGlob(en, normalised) || matchesRegex(e, normalised);
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
