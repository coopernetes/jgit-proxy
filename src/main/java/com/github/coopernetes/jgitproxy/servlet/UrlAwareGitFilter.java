package com.github.coopernetes.jgitproxy.servlet;

import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.http.server.GitSmartHttpTools;

/**
 * A Filter which applies for a matching upstream git provider based on the URL.
 * The filter is applied to all URLs that match the pattern
 * "/{SupportedProviders.hostname}".
 */
@RequiredArgsConstructor
@Slf4j
public abstract class UrlAwareGitFilter implements Filter {
    protected final GitHttpProviders provider;

    @Override
    public final void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var httpRequest = (HttpServletRequest) request;
        log.debug("method={}", httpRequest.getMethod());
        log.debug(
                "headers={}",
                Collections.list(httpRequest.getHeaderNames()).stream()
                        .collect(Collectors.toMap(h -> h, httpRequest::getHeader)));
        log.debug("uri={}", httpRequest.getRequestURI());
        log.debug("query={}", httpRequest.getQueryString());

        if (!isMatchingProvider(httpRequest)) {
            chain.doFilter(request, response);
            return;
        }
        doHttpFilter((HttpServletRequest) request, (HttpServletResponse) response, chain);
    }

    private boolean isMatchingProvider(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/" + provider.getHostname());
    }

    /**
     * Send a Git error response to the client. This is a convenience method to send
     * a 200 response with a message. The message is expected to be a plain text
     * message. The message is written to the response output stream. The response
     * is then closed. git will interpret the message as an error message.
     *
     * @param httpRequest
     *            The request
     * @param httpResponse
     *            The response
     * @param message
     *            The message to send
     */
    protected void sendGitError(HttpServletRequest httpRequest, HttpServletResponse httpResponse, String message)
            throws IOException {
        GitSmartHttpTools.sendError(httpRequest, httpResponse, SC_OK, message);
    }

    /**
     * Perform the filter operation for only HTTP requests. This method is called
     * when the request is determined to be for the matching provider. The request
     * and response are guaranteed to be of type {@link HttpServletRequest} and
     * {@link HttpServletResponse} respectively. There is no reason to override
     * the {@link #doFilter(ServletRequest, ServletResponse, FilterChain)} method.
     * @param request
     * @param response
     * @param chain
     * @throws IOException
     * @throws ServletException
     */
    protected abstract void doHttpFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException;
}
