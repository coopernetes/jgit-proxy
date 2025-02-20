package org.finos.gitproxy.servlet.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jgit.http.server.GitSmartHttpTools;
import org.springframework.core.Ordered;

import java.io.IOException;
import java.util.function.Predicate;

/**
 * Filter that checks if the request is coming from a Git client. If the request is not coming from a Git client, it
 * sends an error response to the client. This filter is used to ensure that only Git clients can be proxied to the
 * target Git provider.
 *
 * <p>Failure to include this filter will allow any HTTP client (including a browser) to access the Git provider through
 * the proxy. This poses a security risk in air-gapped environments where the Git provider should only be accessible via
 * Git clients. Exclude with caution!
 */
public final class ForceGitClientFilter implements GitProxyFilter {

    @Override
    public Predicate<HttpServletRequest> shouldFilter() {
        return (HttpServletRequest request) -> true;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!GitSmartHttpTools.isGitClient(request)) {
            var error = "This endpoint is only accessible via a Git client.";
            request.setAttribute(ERROR_ATTRIBUTE, error);
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, error);
            return;
        }
        chain.doFilter(request, response);
    }
}
