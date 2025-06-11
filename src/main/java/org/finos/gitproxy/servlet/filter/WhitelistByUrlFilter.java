package org.finos.gitproxy.servlet.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
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
class WhitelistByUrlFilter extends AbstractProviderAwareGitProxyFilter implements AuthorizedByUrlFilter {

    private final List<String> whitelist;
    private final Target target;
    private static final Set<HttpOperation> DEFAULT_OPERATIONS = Set.of(HttpOperation.PUSH, HttpOperation.FETCH);
    public static final String WHITELISTED_ATTRIBUTE =
            "org.finos.gitproxy.servlet.filter.WhitelistByUrlFilter.whitelisted";

    public WhitelistByUrlFilter(int order, GitProxyProvider provider, List<String> whitelist, Target target) {
        super(order, DEFAULT_OPERATIONS, provider);
        this.whitelist = whitelist;
        this.target = target;
    }

    public WhitelistByUrlFilter(
            int order,
            Set<HttpOperation> appliedOperations,
            GitProxyProvider provider,
            List<String> whitelist,
            Target target) {
        super(order, appliedOperations, provider);
        this.whitelist = whitelist;
        this.target = target;
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
        if (target == AuthorizedByUrlFilter.Target.OWNER) {
            return o -> o.equals(getOwner(request.getPathInfo()));
        }
        if (target == AuthorizedByUrlFilter.Target.NAME) {
            return n -> n.equals(getName(request.getPathInfo()));
        }
        if (target == AuthorizedByUrlFilter.Target.SLUG) {
            return s -> s.equals(getSlug(request.getPathInfo()));
        }
        throw new IllegalArgumentException("Unknown target type: " + target);
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        // no-op, the aggregate filter will apply the whitelist
    }

    public void applyWhitelist(HttpServletRequest request, HttpServletResponse response) {
        var matcher = createPredicate(target, request);
        if (isAuthorized(matcher)) {
            request.setAttribute(WHITELISTED_ATTRIBUTE, this.toString());
        }
    }

    @Override
    public String toString() {
        return super.toString() + '{' + "whitelist=" + whitelist + ", target=" + target + '}';
    }
}
