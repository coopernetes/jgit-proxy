package com.github.coopernetes.jgitproxy.servlet.filter;

import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.github.coopernetes.jgitproxy.git.HttpOperation;
import com.github.coopernetes.jgitproxy.provider.GitProxyProvider;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.eclipse.jgit.http.server.GitSmartHttpTools;
import org.springframework.core.Ordered;

public interface GitProxyFilter extends Filter, Ordered {

    /**
     * Perform the filter operation for only HTTP requests. This is a convenience method that casts the request and
     * response to {@link HttpServletRequest} and {@link HttpServletResponse} respectively. There is no reason to
     * override this method.
     */
    @Override
    default void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var httpRequest = (HttpServletRequest) request;
        var httpResponse = (HttpServletResponse) response;
        if (shouldFilter(httpRequest)) {
            doHttpFilter(httpRequest, httpResponse, chain);
        }
        chain.doFilter(request, response);
    }

    /**
     * Implement the filtering of git operations for HTTP requests. This method is called when the request is determined
     * to be for the matching provider. The request and response are guaranteed to be of type {@link HttpServletRequest}
     * and {@link HttpServletResponse} respectively. There is no reason to override the {@link #doFilter(ServletRequest,
     * ServletResponse, FilterChain)} method.
     *
     * @param request
     * @param response
     * @param chain
     * @throws IOException
     * @throws ServletException
     */
    void doHttpFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException;

    /**
     * From the request, determine if the filter should be applied. This method is called before the filter is applied
     * to the request. If the filter should be applied, the {@link #doHttpFilter} method is called.
     *
     * @param request
     * @return
     */
    boolean shouldFilter(HttpServletRequest request);

    /**
     * Send a Git error response to the client. This is a convenience method to send a 200 response with a message that
     * a git client will understand. The message is written to the response output stream and the request body is
     * consumed. git will interpret the message as an error message.
     *
     * <p>When called in the context of a {@link jakarta.servlet.Filter#doFilter}, ensure to follow this idiom:
     *
     * <pre>
     *     sendGitError(httpRequest, httpResponse, "failure message");
     *     return;
     * </pre>
     *
     * This is necessary so that the server doesn't attempt any further processing. Failing to do so will result in
     * runtime application errors.
     *
     * @param httpRequest The request
     * @param httpResponse The response
     * @param message The message to send
     */
    default void sendGitError(HttpServletRequest httpRequest, HttpServletResponse httpResponse, String message)
            throws IOException {
        GitSmartHttpTools.sendError(httpRequest, httpResponse, SC_OK, message);
    }

    default HttpOperation determineOperation(HttpServletRequest request) {
        if (GitSmartHttpTools.isUploadPack(request)) {
            return HttpOperation.FETCH;
        } else if (GitSmartHttpTools.isReceivePack(request)) {
            return HttpOperation.PUSH;
        } else if (GitSmartHttpTools.isInfoRefs(request)) {
            return HttpOperation.INFO;
        } else {
            return HttpOperation.UNKNOWN; // For valid git clients, this should never be reached
        }
    }
}
