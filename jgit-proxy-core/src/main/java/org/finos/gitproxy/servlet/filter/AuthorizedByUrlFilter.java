package org.finos.gitproxy.servlet.filter;

import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Predicate;
import org.finos.gitproxy.provider.AbstractGitProxyProvider;

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
public interface AuthorizedByUrlFilter extends RepositoryUrlFilter {

    boolean isAuthorized(Predicate<String> predicate);

    Predicate<String> createPredicate(Target target, HttpServletRequest request);
}
