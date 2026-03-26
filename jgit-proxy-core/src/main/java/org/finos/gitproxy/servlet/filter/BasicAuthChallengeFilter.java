package org.finos.gitproxy.servlet.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.eclipse.jgit.http.server.GitSmartHttpTools;

/**
 * Servlet filter that challenges unauthenticated requests with HTTP 401 and {@code WWW-Authenticate: Basic}. Git
 * clients only send credentials after receiving a 401 challenge — without this, credentials embedded in the remote URL
 * (e.g. {@code http://user:token@proxy/...}) are never transmitted.
 *
 * <p>Only challenges on receive-pack requests (push). Fetch/clone operations are allowed without auth so that public
 * repos remain accessible. Matches both the {@code info/refs?service=git-receive-pack} advertisement and the actual
 * {@code POST /git-receive-pack} data exchange.
 */
public class BasicAuthChallengeFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var httpReq = (HttpServletRequest) request;
        var httpResp = (HttpServletResponse) response;

        if (isReceivePackRequest(httpReq)) {
            String auth = httpReq.getHeader("Authorization");
            if (auth == null || auth.isBlank()) {
                httpResp.setHeader("WWW-Authenticate", "Basic realm=\"git-proxy\"");
                httpResp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private boolean isReceivePackRequest(HttpServletRequest req) {
        // POST to /git-receive-pack (JGit's isReceivePack checks URI + content type)
        if (GitSmartHttpTools.isReceivePack(req)) {
            return true;
        }

        // info/refs?service=git-receive-pack (the ref advertisement before push)
        if (GitSmartHttpTools.isInfoRefs(req)) {
            String service = req.getParameter("service");
            return GitSmartHttpTools.RECEIVE_PACK.equals(service);
        }

        return false;
    }
}
