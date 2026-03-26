package org.finos.gitproxy.servlet.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import org.eclipse.jgit.http.server.GitSmartHttpTools;

/**
 * Servlet filter that forces git smart HTTP error responses to use HTTP 200 status. JGit's internal error handling
 * ({@code GitSmartHttpTools.sendError()}) writes a valid git protocol ERR packet but sets the HTTP status to the error
 * code (e.g. 403). Git clients check the HTTP status first and bail with a generic message like "The requested URL
 * returned error: 403", hiding the actual error message in the protocol body.
 *
 * <p>This filter wraps the response so that any 4xx/5xx status set on a git smart HTTP request is silently replaced
 * with 200. The git client then reads the protocol body and displays: {@code fatal: remote error: <message>}
 */
public class SmartHttpErrorFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var httpReq = (HttpServletRequest) request;
        var httpResp = (HttpServletResponse) response;

        // Only intercept git smart HTTP requests (info/refs, upload-pack, receive-pack)
        if (!isGitSmartRequest(httpReq)) {
            chain.doFilter(request, response);
            return;
        }

        // For receive-pack, use a tiny response buffer so sideband messages (remote: ...)
        // stream to the git client in real time instead of being batched at the end.
        if (GitSmartHttpTools.isReceivePack(httpReq)) {
            httpResp.setBufferSize(256);
        }

        // Wrap response to force 200 status on errors — the error message is in the git protocol body
        chain.doFilter(request, new ForceOkOnErrorResponseWrapper(httpResp));
    }

    private boolean isGitSmartRequest(HttpServletRequest req) {
        return GitSmartHttpTools.isInfoRefs(req)
                || GitSmartHttpTools.isUploadPack(req)
                || GitSmartHttpTools.isReceivePack(req);
    }

    /**
     * Response wrapper that replaces 4xx/5xx status codes with 200, except 401 Unauthorized which must pass through so
     * git clients send credentials. JGit's error handling already writes the error message in git protocol format (ERR
     * pkt-line) — we just need the HTTP status to be 200 so the git client actually reads and displays it.
     */
    private static class ForceOkOnErrorResponseWrapper extends HttpServletResponseWrapper {

        ForceOkOnErrorResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setStatus(int sc) {
            // Don't intercept 401 — auth challenges must reach the client so it sends credentials
            super.setStatus(sc >= 400 && sc != HttpServletResponse.SC_UNAUTHORIZED ? HttpServletResponse.SC_OK : sc);
        }

        @Override
        public void sendError(int sc, String msg) throws IOException {
            if (sc == HttpServletResponse.SC_UNAUTHORIZED) {
                super.sendError(sc, msg);
            } else {
                super.setStatus(HttpServletResponse.SC_OK);
            }
        }

        @Override
        public void sendError(int sc) throws IOException {
            if (sc == HttpServletResponse.SC_UNAUTHORIZED) {
                super.sendError(sc);
            } else {
                super.setStatus(HttpServletResponse.SC_OK);
            }
        }
    }
}
