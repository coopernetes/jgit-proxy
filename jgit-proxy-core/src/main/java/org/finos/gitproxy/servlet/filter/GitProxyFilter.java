package org.finos.gitproxy.servlet.filter;

import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.finos.gitproxy.servlet.GitProxyProviderServlet.*;
import static org.finos.gitproxy.servlet.GitProxyServlet.*;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Set;
import java.util.function.Predicate;
import org.eclipse.jgit.http.server.GitSmartHttpTools;
import org.finos.gitproxy.db.model.PushStep;
import org.finos.gitproxy.db.model.StepStatus;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
// import org.springframework.core.Ordered;

/**
 * A {@link Filter} with additional methods that are designed to be registered with a
 * {@link org.finos.gitproxy.servlet.GitProxyProviderServlet} for the purposes of filtering git operations. This
 * interface presumes that the filter is designed to be used with HTTP requests and provides a method to filter only
 * HTTP requests. Classes implementing this interface signal whether this filter should be applied to a given request
 * using a {@link Predicate}.
 *
 * <p>Custom filters should generally extend {@link AbstractGitProxyFilter} or
 * {@link AbstractProviderAwareGitProxyFilter} {@see AbstractGitProxyFilter} {@see AbstractProviderAwareGitProxyFilter}
 *
 * <h2>Filter Order Ranges</h2>
 *
 * Filters are executed in order based on their {@link #getOrder()} value. The following ranges are reserved:
 *
 * <ul>
 *   <li><b>Preprocessing filters (Integer.MIN_VALUE to Integer.MIN_VALUE+99):</b> Core system filters that must run
 *       first (e.g., ForceGitClientFilter, ParseGitRequestFilter, EnrichPushCommitsFilter)
 *   <li><b>Custom preprocessing filters (1-999):</b> Reserved for user-defined filters that need to run before
 *       whitelisting
 *   <li><b>Whitelist filters (1000-1999):</b> Authorization filters that determine if a request is allowed. Up to 1000
 *       custom whitelist filters can be configured in this range.
 *   <li><b>Built-in content filters (2000-4999):</b> Core validation filters (email checks, commit message validation,
 *       GPG signatures, etc.). Built-in filters use multiples of 100 (2000, 2100, 2200, etc.) to allow up to 99 custom
 *       filters between each built-in filter.
 *   <li><b>Custom post-processing filters (5000+):</b> User-defined filters that run after all built-in filters
 *   <li><b>Audit filters (Integer.MAX_VALUE):</b> Audit and logging filters that should run last
 * </ul>
 */
public interface GitProxyFilter extends Filter {

    Set<HttpOperation> ALL_OPERATIONS = Set.of(HttpOperation.values());
    Set<HttpOperation> DEFAULT_OPERATIONS = Set.of(HttpOperation.PUSH, HttpOperation.FETCH);

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
    void doHttpFilter(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException;

    int getOrder();

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
    /**
     * Whether this filter should be skipped for ref-deletion pushes (commitTo = zero SHA). Content validation filters
     * return {@code true} (the default) because there are no commits to validate. Auth and whitelist filters that must
     * still gate deletions should override this to return {@code false}.
     */
    default boolean skipForRefDeletion() {
        return true;
    }

    @Override
    default void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Short-circuit if a prior filter pre-approved this push (e.g. AllowApprovedPushFilter)
        if (Boolean.TRUE.equals(httpRequest.getAttribute(PRE_APPROVED_ATTR))) {
            chain.doFilter(request, response);
            return;
        }

        // Skip content filters for ref deletions — there are no commits to validate
        var detailsForDeletion = (GitRequestDetails) httpRequest.getAttribute(GIT_REQUEST_ATTR);
        if (detailsForDeletion != null && detailsForDeletion.isRefDeletion() && skipForRefDeletion()) {
            chain.doFilter(request, response);
            return;
        }

        // Only process if this filter should run
        if (shouldFilter().test(httpRequest)) {
            addFilterToDetails(httpRequest);
            var details = (GitRequestDetails) httpRequest.getAttribute(GIT_REQUEST_ATTR);
            int stepsBefore = details != null ? details.getSteps().size() : 0;
            // Execute filter logic
            doHttpFilter(httpRequest, httpResponse);

            // If response was committed (blockAndSendError), stop the chain immediately
            if (httpResponse.isCommitted()) {
                return;
            }
            // Auto-record PASS only when doHttpFilter didn't record its own step.
            // Filters using recordIssue() add a BLOCKED step without committing the response,
            // so we must not overwrite that with a spurious PASS.
            int stepsAfter = details != null ? details.getSteps().size() : 0;
            if (stepsAfter == stepsBefore) {
                recordStep(httpRequest, StepStatus.PASS, null, null);
            }
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
     * Record a filter step result on the request for later persistence. Each filter that runs should have its outcome
     * recorded as a {@link PushStep} so the audit trail captures which filters passed and which blocked.
     *
     * @param request The HTTP request
     * @param status The step result (PASS, BLOCKED, FAIL, SKIPPED)
     * @param reason Short reason (for BLOCKED/FAIL steps)
     * @param content Detailed content (e.g., the formatted error message sent to the client)
     */
    default void recordStep(HttpServletRequest request, StepStatus status, String reason, String content) {
        var details = (GitRequestDetails) request.getAttribute(GIT_REQUEST_ATTR);
        if (details == null) return;

        PushStep step = PushStep.builder()
                .pushId(details.getId().toString())
                .stepName(this.getClass().getSimpleName())
                .stepOrder(this.getOrder())
                .status(status)
                .content(content)
                .blockedMessage(status == StepStatus.BLOCKED ? reason : null)
                .errorMessage(status == StepStatus.FAIL ? reason : null)
                .build();
        details.getSteps().add(step);
    }

    /**
     * Record a validation issue without committing the HTTP response. Use this in the transparent proxy pipeline to
     * collect all validation failures before sending a combined error via {@link ValidationSummaryFilter}. Unlike
     * {@link #rejectAndSendError}, this method does not send a response, so subsequent filters continue to run.
     *
     * @param request The HTTP request
     * @param reason Short reason for the block (stored in the DB for querying/reporting)
     * @param formattedMessage The full formatted message (stored as step content for later display)
     */
    default void recordIssue(HttpServletRequest request, String reason, String formattedMessage) {
        setResult(request, GitRequestDetails.GitResult.REJECTED, reason);
        recordStep(request, StepStatus.FAIL, reason, formattedMessage);
    }

    /**
     * Block the push and send an error response to the git client, recording both the result and the step in one call.
     * This replaces the three-step pattern of {@code setResult} + {@code sendGitError} + {@code return}.
     *
     * <p>Usage:
     *
     * <pre>
     *     blockAndSendError(request, response, "Illegal author emails", formattedMessage);
     *     return;
     * </pre>
     *
     * @param request The HTTP request
     * @param response The HTTP response
     * @param reason Short reason for the block (stored in the DB for querying/reporting)
     * @param formattedMessage The full formatted message to display to the git client (also stored as step content)
     */
    default void rejectAndSendError(
            HttpServletRequest request, HttpServletResponse response, String reason, String formattedMessage)
            throws IOException {
        setResult(request, GitRequestDetails.GitResult.REJECTED, reason);
        recordStep(request, StepStatus.FAIL, reason, formattedMessage);
        String serviceUrl = (String) request.getAttribute(SERVICE_URL_ATTR);
        String fullMessage =
                serviceUrl != null ? formattedMessage + "\n\nView pending pushes at: " + serviceUrl : formattedMessage;
        sendGitError(request, response, fullMessage);
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
        var details = (GitRequestDetails) request.getAttribute(GIT_REQUEST_ATTR);
        if (details != null) {
            details.getFilters().add(this);
        }
    }

    default void setResult(HttpServletRequest request, GitRequestDetails.GitResult result, String reason) {
        var details = (GitRequestDetails) request.getAttribute(GIT_REQUEST_ATTR);
        if (details != null) {
            details.setResult(result);
            details.setReason(reason);
        }
    }
}
