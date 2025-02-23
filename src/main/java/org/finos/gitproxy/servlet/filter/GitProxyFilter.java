package org.finos.gitproxy.servlet.filter;

import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.finos.gitproxy.servlet.GitProxyProviderServlet.GIT_REQUEST_ATTRIBUTE;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.function.Predicate;
import org.eclipse.jgit.http.server.GitSmartHttpTools;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.springframework.core.Ordered;

/**
 * An {@link Ordered} {@link Filter} with additional methods that are designed to be registered with a
 * {@link org.finos.gitproxy.servlet.GitProxyProviderServlet} for the purposes of filtering git operations. This
 * interface presumes that the filter is designed to be used with HTTP requests and provides a method to filter only
 * HTTP requests. Classes implementing this interface signal whether this filter should be applied to a given request
 * using a {@link Predicate}.
 *
 * <p>Custom filters should generally extend {@link AbstractGitProxyFilter} or
 * {@link AbstractProviderAwareGitProxyFilter} {@see AbstractGitProxyFilter} {@see AbstractProviderAwareGitProxyFilter}
 */
public interface GitProxyFilter extends Filter, Ordered {

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
     * applied to the request. If the filter should be applied, {@link #doHttpFilter} method is called. This method
     * replaces the "continue" field from the <a href="
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
        if (shouldFilter().test((httpRequest))) {
            addFilterToDetails(httpRequest);
            doHttpFilter(httpRequest, httpResponse, chain);
        }
        chain.doFilter(request, response);
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
            var headers = new HashMap<String, String>();
            request.getHeaderNames().asIterator().forEachRemaining(name -> headers.put(name, request.getHeader(name)));
            if (headers.containsKey("Authorization")) {
                headers.put("Authorization", "REDACTED");
            }
            System.out.println("Unknown git operation. " + request.getRequestURI() + " " + request.getPathInfo() + " "
                    + request.getQueryString() + " " + headers);
            throw new IllegalArgumentException("Unknown git operation");
        }
    }

    //    default boolean isClone(HttpServletRequest request) {
    //        return request.getRequestURI().endsWith("/HEAD") &&
    //                request.getHeader("user-agent").startsWith("git/");
    //    }

    default void addFilterToDetails(HttpServletRequest request) {
        var details = (GitRequestDetails) request.getAttribute(GIT_REQUEST_ATTRIBUTE);
        if (details != null) {
            details.getFilters().add(this);
        }
    }

    default void setResult(HttpServletRequest request, GitRequestDetails.GitResult result, String reason) {
        var details = (GitRequestDetails) request.getAttribute(GIT_REQUEST_ATTRIBUTE);
        if (details != null) {
            details.setResult(result);
            details.setReason(reason);
        }
    }
}
