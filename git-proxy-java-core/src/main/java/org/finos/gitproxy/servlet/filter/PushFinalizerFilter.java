package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.git.GitClientUtils.AnsiColor.CYAN;
import static org.finos.gitproxy.git.GitClientUtils.SymbolCodes.LINK;
import static org.finos.gitproxy.git.GitClientUtils.buildValidationSummary;
import static org.finos.gitproxy.git.GitClientUtils.color;
import static org.finos.gitproxy.git.GitClientUtils.sym;
import static org.finos.gitproxy.servlet.GitProxyServlet.*;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.approval.ApprovalGateway;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;

/**
 * Terminal filter for push operations that determines the final result:
 *
 * <ul>
 *   <li>If {@code preApproved} is set (re-push of an approved push), marks the request as
 *       {@link GitRequestDetails.GitResult#ALLOWED} and lets it through to the upstream proxy.
 *   <li>If the push passed all validation (result is still {@link GitRequestDetails.GitResult#PENDING}), marks it as
 *       {@link GitRequestDetails.GitResult#REVIEW} (pending review) and sends a git error with a link to the dashboard.
 *   <li>If validation already rejected the push ({@code REJECTED} or {@code ERROR}), does nothing - the response was
 *       already committed by {@link ValidationSummaryFilter}.
 * </ul>
 *
 * <p>This filter deliberately overrides {@link #doFilter} to bypass the {@code preApproved} short-circuit in
 * {@link GitProxyFilter} - it must always run to set the final result status regardless of approval state.
 *
 * <p>Runs at order {@code Integer.MAX_VALUE - 1}, after all content validation filters and
 * {@link ValidationSummaryFilter}.
 */
@Slf4j
public class PushFinalizerFilter extends AbstractGitProxyFilter {

    private static final int ORDER = Integer.MAX_VALUE - 1;

    private final String serviceUrl;
    private final ApprovalGateway approvalGateway;

    public PushFinalizerFilter(String serviceUrl, ApprovalGateway approvalGateway) {
        super(ORDER, Set.of(HttpOperation.PUSH));
        this.serviceUrl = serviceUrl;
        this.approvalGateway = approvalGateway;
    }

    /**
     * Override doFilter directly so this filter is NOT short-circuited by preApproved. The finalizer must always run to
     * stamp the correct result on the request details.
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        if (shouldFilter().test(httpRequest)) {
            addFilterToDetails(httpRequest);
            doHttpFilter(httpRequest, httpResponse);

            if (httpResponse.isCommitted()) {
                return;
            }

            // Rewrite outbound credentials if an upstream username was resolved (Bitbucket only).
            // Must happen after doHttpFilter sets the ALLOWED result so we only rewrite for approved pushes.
            var details = (GitRequestDetails) httpRequest.getAttribute(GIT_REQUEST_ATTR);
            if (details != null
                    && details.getResult() == GitRequestDetails.GitResult.ALLOWED
                    && details.getUpstreamUsername() != null) {
                request = rewriteBasicAuthUsername(httpRequest, details.getUpstreamUsername());
            }
        }
        chain.doFilter(request, response);
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        var details = (GitRequestDetails) request.getAttribute(GIT_REQUEST_ATTR);
        if (details == null) {
            return;
        }

        // Ref deletions are always allowed through - no approval needed
        if (details.isRefDeletion()) {
            details.setResult(GitRequestDetails.GitResult.ALLOWED);
            return;
        }

        // If validation already rejected or errored, the response is committed - nothing to do
        if (details.getResult() == GitRequestDetails.GitResult.REJECTED
                || details.getResult() == GitRequestDetails.GitResult.ERROR) {
            return;
        }

        // Re-push of an approved push - allow it through
        if (Boolean.TRUE.equals(request.getAttribute(PRE_APPROVED_ATTR))) {
            details.setResult(GitRequestDetails.GitResult.ALLOWED);
            return;
        }

        // If the gateway approves immediately (e.g. auto mode), forward the push without blocking
        if (approvalGateway.approvesImmediately()) {
            details.setResult(GitRequestDetails.GitResult.ALLOWED);
            return;
        }

        // First push that passed validation - block pending review (dashboard/ServiceNow mode).
        // Self-certify is intentionally NOT enforced here: the role check requires Spring Security context
        // which the proxy filter chain does not have. Self-approval is gated entirely in the dashboard
        // (PushController.checkReviewerIdentity), where both ROLE_SELF_CERTIFY and the SELF_CERTIFY repo
        // permission are required. The pre-receive hook re-verifies the per-repo permission as defense in
        // depth before forwarding an approved self-review.
        details.setResult(GitRequestDetails.GitResult.REVIEW);
        String pushId = details.getId().toString();
        String summary = buildValidationSummary(details.getSteps());
        String divider = "\n────────────────────────────────────────\n";
        String link = color(CYAN, sym(LINK) + "  View push record: " + serviceUrl + "/push/" + pushId);
        String fullMessage = summary + divider + link;
        sendGitError(request, response, fullMessage);
    }

    /**
     * Returns a wrapper that replaces the {@code Authorization} Basic-auth username with {@code upstreamUsername},
     * keeping the password unchanged. Used to rewrite Bitbucket credentials from {@code email:token} to
     * {@code username:token} before the request is forwarded upstream.
     */
    private static HttpServletRequest rewriteBasicAuthUsername(HttpServletRequest request, String upstreamUsername) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            return request;
        }
        try {
            String decoded = new String(Base64.getDecoder()
                    .decode(authHeader.substring("Basic ".length()).trim()));
            int colon = decoded.indexOf(':');
            if (colon < 0) return request;
            String token = decoded.substring(colon + 1);
            String rewritten =
                    "Basic " + Base64.getEncoder().encodeToString((upstreamUsername + ":" + token).getBytes());
            log.debug("Rewriting Bitbucket outbound Authorization header username to '{}'", upstreamUsername);
            return new HttpServletRequestWrapper(request) {
                @Override
                public String getHeader(String name) {
                    if ("Authorization".equalsIgnoreCase(name)) return rewritten;
                    return super.getHeader(name);
                }

                @Override
                public Enumeration<String> getHeaders(String name) {
                    if ("Authorization".equalsIgnoreCase(name)) return Collections.enumeration(Set.of(rewritten));
                    return super.getHeaders(name);
                }
            };
        } catch (IllegalArgumentException e) {
            log.warn("Failed to rewrite Basic auth header for upstream Bitbucket push", e);
            return request;
        }
    }
}
