package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
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
 */
@Slf4j
public class WhitelistByUrlFilter extends AbstractProviderAwareGitProxyFilter implements AuthorizedByUrlFilter {

    private final List<String> whitelist;
    private final Target target;
    public static final String WHITELISTED_BY_ATTRIBUTE =
            "org.finos.gitproxy.servlet.filter.WhitelistByUrlFilter.whitelistedBy";

    // Whitelist filters must be in the range 1000-1999
    private static final int MIN_WHITELIST_ORDER = 1000;
    private static final int MAX_WHITELIST_ORDER = 1999;

    public WhitelistByUrlFilter(int order, GitProxyProvider provider, List<String> whitelist, Target target) {
        super(validateWhitelistOrder(order), DEFAULT_OPERATIONS, provider);
        this.whitelist = whitelist;
        this.target = target;
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
                    "Whitelist filter order must be between %d and %d (inclusive), but was %d",
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
            return o -> o.equals(details.getRepository().getOwner());
        }
        if (target == AuthorizedByUrlFilter.Target.NAME) {
            return o -> o.equals(details.getRepository().getName());
        }
        if (target == AuthorizedByUrlFilter.Target.SLUG) {
            return o -> o.equals(details.getRepository().getSlug());
        }
        throw new IllegalArgumentException("Unknown target type: " + target);
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
