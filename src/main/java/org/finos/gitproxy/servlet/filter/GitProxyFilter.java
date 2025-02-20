package org.finos.gitproxy.servlet.filter;

import org.finos.gitproxy.git.HttpOperation;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jgit.http.server.GitSmartHttpTools;
import org.springframework.core.Ordered;

import java.io.IOException;
import java.util.function.Predicate;

import static jakarta.servlet.http.HttpServletResponse.SC_OK;

public interface GitProxyFilter extends Filter, Ordered {

    String ERROR_ATTRIBUTE = "com.github.coopernetes.jgitproxy.error";

    /**
     * Implement the filtering of git operations for HTTP requests. This method is called when the request is determined
     * to be for the matching provider. The request and response are guaranteed to be of type {@link HttpServletRequest}
     * and {@link HttpServletResponse} respectively. There is no reason to override the {@link #doFilter(ServletRequest,
     * ServletResponse, FilterChain)} method.
     *
     * @param request The request to filter
     * @param response
     * @param chain
     * @throws IOException
     * @throws ServletException
     */
    void doHttpFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException;

    /**
     * From the request details, determine if the filter should be applied. This method is called before the filter is
     * applied to the request. If the filter should be applied, {@link #doHttpFilter} method is called.
     *
     * @return A predicate that determines if the filter should be applied
     */
    Predicate<HttpServletRequest> shouldFilter();

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

    default boolean shouldFilter(HttpServletRequest request) {
        return shouldFilter().test(request);
    }

    /**
     * Send a Git error response to the client. This is a convenience method to send a 200 response with a message that
     * a git client will understand. The message is written to the response output stream and the request body is
     * consumed. git will interpret the message as an error message.
     *
     * <p>When used inside a {@link #doHttpFilter} method, ensure to follow this pattern:
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

    /**
     * Determine the git operation from the request. This is a convenience method that determines the git operation
     * based on the request. The default implementation uses the {@link GitSmartHttpTools} to determine the operation.
     *
     * @param request The request
     * @return The git operation
     */
    default HttpOperation determineOperation(HttpServletRequest request) {
        if (GitSmartHttpTools.isUploadPack(request)) {
            return HttpOperation.FETCH;
        } else if (GitSmartHttpTools.isReceivePack(request)) {
            return HttpOperation.PUSH;
        } else if (GitSmartHttpTools.isInfoRefs(request)) {
            return HttpOperation.INFO;
        } else {
            throw new IllegalArgumentException("Unknown git operation");
        }
    }
}
