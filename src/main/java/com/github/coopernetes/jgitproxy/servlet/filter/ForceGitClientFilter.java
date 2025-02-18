package com.github.coopernetes.jgitproxy.servlet.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.function.Predicate;
import org.eclipse.jgit.http.server.GitSmartHttpTools;
import org.springframework.core.Ordered;

/**
 * Filter that checks if the request is coming from a Git client. If the request is not coming from a Git client, it
 * sends an error response to the client. This filter is used to ensure that only Git clients can be proxied to the
 * target Git provider.
 */
public class ForceGitClientFilter implements GitProxyFilter {

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
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "This endpoint is only accessible via Git client.");
            return;
        }
        chain.doFilter(request, response);
    }
}
