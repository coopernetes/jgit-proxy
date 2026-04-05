package org.finos.gitproxy.servlet.filter;

import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Predicate;

/**
 * A filter that checks a given URL against a predicate to determine if it is authorized. This is typically used to
 * implement a whitelist of authorized projects by name or owner in a given provider based on a URL pattern.
 */
public interface AuthorizedByUrlFilter {

    enum Target {
        OWNER,
        NAME,
        SLUG;
    }

    boolean isAuthorized(Predicate<String> predicate);

    Predicate<String> createPredicate(Target target, HttpServletRequest request);
}
