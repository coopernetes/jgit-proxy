package com.github.coopernetes.jgitproxy.servlet.filter;

import com.github.coopernetes.jgitproxy.git.HttpOperation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.eclipse.jgit.http.server.GitSmartHttpTools;
import org.springframework.core.Ordered;

/**
 * Filter that checks if the request is coming from a Git client. If the request is not coming from a Git client, it
 * sends an error response to the client. This filter is used to ensure that only Git clients can be proxied to the
 * target Git provider.
 */
public class ForceGitClientFilter extends AbstractGitProxyFilter {

    public ForceGitClientFilter() {
        super(
                Ordered.HIGHEST_PRECEDENCE,
                Set.of(HttpOperation.PUSH, HttpOperation.FETCH, HttpOperation.INFO, HttpOperation.UNKNOWN));
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
