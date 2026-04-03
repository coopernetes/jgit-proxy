package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.git.GitClientUtils.AnsiColor.CYAN;
import static org.finos.gitproxy.git.GitClientUtils.SymbolCodes.LINK;
import static org.finos.gitproxy.git.GitClientUtils.buildValidationSummary;
import static org.finos.gitproxy.git.GitClientUtils.color;
import static org.finos.gitproxy.git.GitClientUtils.sym;
import static org.finos.gitproxy.servlet.GitProxyServlet.*;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;

/**
 * Terminal filter for push operations that determines the final result:
 *
 * <ul>
 *   <li>If {@code preApproved} is set (re-push of an approved push), marks the request as
 *       {@link GitRequestDetails.GitResult#ALLOWED} and lets it through to the upstream proxy.
 *   <li>If the push passed all validation (result is still {@link GitRequestDetails.GitResult#PENDING}), marks it as
 *       {@link GitRequestDetails.GitResult#BLOCKED} (pending review) and sends a git error with a link to the
 *       dashboard.
 *   <li>If validation already rejected the push ({@code REJECTED} or {@code ERROR}), does nothing — the response was
 *       already committed by {@link ValidationSummaryFilter}.
 * </ul>
 *
 * <p>This filter deliberately overrides {@link #doFilter} to bypass the {@code preApproved} short-circuit in
 * {@link GitProxyFilter} — it must always run to set the final result status regardless of approval state.
 *
 * <p>Runs at order 5000, after all content validation filters and {@link ValidationSummaryFilter}.
 */
public class PushFinalizerFilter extends AbstractGitProxyFilter {

    private static final int ORDER = 5000;

    private final String serviceUrl;

    public PushFinalizerFilter(String serviceUrl) {
        super(ORDER, Set.of(HttpOperation.PUSH));
        this.serviceUrl = serviceUrl;
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

        // Ref deletions are always allowed through — no approval needed
        if (details.isRefDeletion()) {
            details.setResult(GitRequestDetails.GitResult.ALLOWED);
            return;
        }

        // If validation already rejected or errored, the response is committed — nothing to do
        if (details.getResult() == GitRequestDetails.GitResult.REJECTED
                || details.getResult() == GitRequestDetails.GitResult.ERROR) {
            return;
        }

        // Re-push of an approved push — allow it through
        if (Boolean.TRUE.equals(request.getAttribute(PRE_APPROVED_ATTR))) {
            details.setResult(GitRequestDetails.GitResult.ALLOWED);
            return;
        }

        // First push that passed validation — block pending review
        details.setResult(GitRequestDetails.GitResult.BLOCKED);
        String pushId = details.getId().toString();
        String summary = buildValidationSummary(details.getSteps());
        String divider = "\n────────────────────────────────────────\n";
        String link = color(CYAN, sym(LINK) + "  View push record: " + serviceUrl + "/#/push/" + pushId);
        String fullMessage = summary + divider + link;
        sendGitError(request, response, fullMessage);
    }
}
