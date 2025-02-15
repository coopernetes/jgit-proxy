package com.github.coopernetes.jgitproxy.servlet.filter;

import com.github.coopernetes.jgitproxy.provider.AbstractGitProxyProvider;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Predicate;
import org.springframework.util.Assert;

/**
 * A filter that checks a given URL against a predicate to determine if it is authorized. This is typically used to
 * implement a whitelist of authorized projects by name or owner in a given provider based on a URL pattern. The default
 * methods in this interface provide a typical mechanism to extract the owner, name or the full "slug" of a given git
 * repository by its URL path for common providers (GitHub, GitLab, BitBucket).
 *
 * <p>Care must be taken on what {@link AbstractGitProxyProvider} this filter is activated for. Not Git server is
 * expected to use the same URL scheme and the default methods will raise an {@link IllegalArgumentException} if used
 * against a URL which does not follow this pattern. For use with servers that use a different URL pattern, it is
 * recommended to implement this interface's methods.
 */
public interface AuthorizedByUrlFilter {

    boolean isAuthorized(Predicate<String> predicate);

    enum Target {
        OWNER,
        NAME,
        SLUG;
    }

    default Predicate<String> createPredicate(Target target, HttpServletRequest request) {
        if (target == AuthorizedByUrlFilter.Target.OWNER) {
            return o -> o.equals(getOwner(request.getRequestURI()));
        }
        if (target == AuthorizedByUrlFilter.Target.NAME) {
            return n -> n.equals(getName(request.getRequestURI()));
        }
        if (target == AuthorizedByUrlFilter.Target.SLUG) {
            return s -> s.equals(getSlug(request.getRequestURI()));
        }
        throw new IllegalArgumentException("Unknown target type: " + target);
    }

    /**
     * Extracts the repository slug from the request URI. The slug is 2nd & 3rd path segments of the URI and formatted
     * as `owner/repo`. Any additional path segments are ignored.
     *
     * <pre>
     *     /{providerHost}/{owner}/{name}/...
     * </pre>
     *
     * @param requestUri the result of {@link HttpServletRequest#getRequestURI()}
     * @return the repository slug
     */
    default String getSlug(String requestUri) {
        var parts = getUriParts(requestUri);
        return parts[2] + "/" + parts[3];
    }

    /**
     * Extracts the owner of the repository from the request URI. It is derived from the 2nd path segment of the URI.
     *
     * <pre>
     *     /{providerHost}/{owner}/...
     *     </pre>
     *
     * @param requestUri the result of {@link HttpServletRequest#getRequestURI()}
     * @return the repository owner
     */
    default String getOwner(String requestUri) {
        var parts = getUriParts(requestUri);
        return parts[2];
    }

    /**
     * Extracts the name of the repository from the request URI. It is derived from the 3rd path segment of the URI.
     *
     * <pre>
     *     /{providerHost}/{owner}/{name}/...
     * </pre>
     *
     * @param requestUri the result of {@link HttpServletRequest#getRequestURI()}
     * @return the repository owner
     */
    default String getName(String requestUri) {
        var parts = getUriParts(requestUri);
        return parts[3];
    }

    private String[] getUriParts(String requestUri) {
        Assert.notNull(requestUri, "URI must not be null");
        var parts = requestUri.split("/");
        Assert.isTrue(
                parts.length >= 4,
                "URI must have at least 4 parts to be used by this filter ("
                        + this.getClass().getSimpleName() + ")");
        return parts;
    }
}
