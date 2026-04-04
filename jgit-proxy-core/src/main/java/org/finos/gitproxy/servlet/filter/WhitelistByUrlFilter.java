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
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.provider.GitProxyProvider;

/**
 * A generic whitelist filter that checks if the request is authorized to perform any {@link #applicableOperations}
 * based on the {@link #target} and a provided list of Strings that form the whitelist to compare against. Any request
 * URL, which is parsed according to the target, that does not match any of the whitelist values will result in an error
 * being returned to the client. The whitelist is typically created via configuration (Spring application properties).
 * Custom implementations can provide this list from any source.
 *
 * <p>This filter accepts any {@link GitProxyProvider}. Care must be taken to ensure that the configured provider
 * follows the common URL scheme when using the default behaviour of this class. Most of the request matching logic is
 * delegated to {@link AuthorizedByUrlFilter} which is implemented by this class.
 *
 * <p>Whitelist entries may use glob syntax ({@code *}, {@code ?}, {@code [...]}) as defined by
 * {@link java.nio.file.FileSystem#getPathMatcher}. For slug patterns spanning both owner and name (e.g.
 * {@code owner-prefix&#47;*}), use {@code /} as the separator — this mirrors the standard {@code owner/repo} slug
 * format. Exact matches are always evaluated first; glob patterns are only consulted when no exact match is found.
 */
@Slf4j
public class WhitelistByUrlFilter extends AbstractProviderAwareGitProxyFilter implements AuthorizedByUrlFilter {

    private final List<String> whitelist;
    private final Target target;
    private final Map<String, PathMatcher> globPatterns;

    public static final String WHITELISTED_BY_ATTRIBUTE =
            "org.finos.gitproxy.servlet.filter.WhitelistByUrlFilter.whitelistedBy";

    // Whitelist filters must be in the authorization range 50-199
    private static final int MIN_WHITELIST_ORDER = 50;
    private static final int MAX_WHITELIST_ORDER = 199;

    public WhitelistByUrlFilter(int order, GitProxyProvider provider, List<String> whitelist, Target target) {
        super(validateWhitelistOrder(order), DEFAULT_OPERATIONS, provider);
        this.whitelist = whitelist;
        this.target = target;
        this.globPatterns = compileGlobPatterns(whitelist);
    }

    public WhitelistByUrlFilter(
            int order,
            Set<HttpOperation> appliedOperations,
            GitProxyProvider provider,
            List<String> whitelist,
            Target target) {
        super(validateWhitelistOrder(order), appliedOperations, provider);
        this.whitelist = whitelist;
        this.target = target;
        this.globPatterns = compileGlobPatterns(whitelist);
    }

    private static Map<String, PathMatcher> compileGlobPatterns(List<String> whitelist) {
        Map<String, PathMatcher> patterns = new LinkedHashMap<>();
        for (String entry : whitelist) {
            if (isGlobPattern(entry)) {
                patterns.put(entry, FileSystems.getDefault().getPathMatcher("glob:" + entry));
                log.debug("Compiled glob whitelist pattern: {}", entry);
            }
        }
        return Collections.unmodifiableMap(patterns);
    }

    private static boolean isGlobPattern(String s) {
        return s.contains("*") || s.contains("?") || s.contains("[");
    }

    /**
     * Validates that the whitelist filter order is within the allowed range.
     *
     * @param order The filter order
     * @return The validated order
     * @throws IllegalArgumentException if order is outside the allowed range
     */
    private static int validateWhitelistOrder(int order) {
        if (order < MIN_WHITELIST_ORDER || order > MAX_WHITELIST_ORDER) {
            throw new IllegalArgumentException(String.format(
                    "Whitelist filter order must be in the authorization range %d-%d (inclusive), but was %d",
                    MIN_WHITELIST_ORDER, MAX_WHITELIST_ORDER, order));
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

    @Override
    public boolean isAuthorized(Predicate<String> predicate) {
        return whitelist.stream().anyMatch(predicate);
    }

    @Override
    public Predicate<String> createPredicate(Target target, HttpServletRequest request) {
        var details = (GitRequestDetails) request.getAttribute(GIT_REQUEST_ATTR);
        if (target == AuthorizedByUrlFilter.Target.OWNER) {
            String owner = details.getRepoRef().getOwner();
            return o -> o.equals(owner) || matchesGlob(o, owner);
        }
        if (target == AuthorizedByUrlFilter.Target.NAME) {
            String name = details.getRepoRef().getName();
            return o -> o.equals(name) || matchesGlob(o, name);
        }
        if (target == AuthorizedByUrlFilter.Target.SLUG) {
            String slug = details.getRepoRef().getSlug();
            return o -> o.equals(slug) || matchesGlob(o, slug);
        }
        throw new IllegalArgumentException("Unknown target type: " + target);
    }

    private boolean matchesGlob(String pattern, String value) {
        PathMatcher matcher = globPatterns.get(pattern);
        if (matcher == null) return false;
        return matcher.matches(Paths.get(value));
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response) {
        // no-op, the aggregate filter will apply the whitelist
    }

    public void applyWhitelist(HttpServletRequest request) {
        var matcher = createPredicate(target, request);
        if (isAuthorized(matcher)) {
            request.setAttribute(WHITELISTED_BY_ATTRIBUTE, this.toString());
        }
    }

    @Override
    public String toString() {
        return "WhitelistByUrlFilter{" + "whitelist=" + whitelist + ", target=" + target + '}';
    }
}
