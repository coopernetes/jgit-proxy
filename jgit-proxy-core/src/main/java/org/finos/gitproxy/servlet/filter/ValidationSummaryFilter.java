package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.git.GitClientUtils.AnsiColor.*;
import static org.finos.gitproxy.git.GitClientUtils.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClientUtils.buildValidationSummary;
import static org.finos.gitproxy.git.GitClientUtils.color;
import static org.finos.gitproxy.git.GitClientUtils.sym;
import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;
import static org.finos.gitproxy.servlet.GitProxyServlet.SERVICE_URL_ATTR;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.db.model.PushStep;
import org.finos.gitproxy.db.model.StepStatus;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;

/**
 * Terminal validation filter for the transparent proxy pipeline. Runs after all content validation filters and sends a
 * combined error response if any of them recorded a validation issue via {@link GitProxyFilter#recordIssue}. This
 * ensures developers see ALL validation failures in a single push attempt rather than stopping at the first one.
 *
 * <p>If no issues were recorded the request is passed through to the upstream proxy servlet unchanged.
 *
 * <p>Blocked pushes are persisted with status {@link GitRequestDetails.GitResult#BLOCKED} and enter the manual approval
 * queue. When an admin approves the push in the UI, the user can re-push the same commit to have it pass through
 * automatically (detected by {@link AllowApprovedPushFilter}).
 *
 * <p>This filter runs at order 4999, after all built-in content filters (2000–4999).
 */
@Slf4j
public class ValidationSummaryFilter extends AbstractGitProxyFilter {

    private static final int ORDER = 4999;

    public ValidationSummaryFilter() {
        super(ORDER, Set.of(HttpOperation.PUSH));
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var details = (GitRequestDetails) request.getAttribute(GIT_REQUEST_ATTR);
        if (details == null || details.getResult() != GitRequestDetails.GitResult.REJECTED) {
            return;
        }

        List<String> messages = details.getSteps().stream()
                .filter(s -> s.getStatus() == StepStatus.FAIL)
                .map(PushStep::getContent)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        int count = messages.size();
        log.warn("Blocking push pending review — {} validation check(s) failed", count);

        String divider = "────────────────────────────────────────";
        String summary = buildValidationSummary(details.getSteps());
        String header = color(RED, sym(NO_ENTRY) + "  Push Blocked — " + count + " validation issue(s)");
        String body = String.join("\n", messages);
        String combined = summary + divider + "\n" + header + "\n" + body;

        setResult(request, GitRequestDetails.GitResult.REJECTED, count + " validation issue(s)");
        String serviceUrl = (String) request.getAttribute(SERVICE_URL_ATTR);
        String link = serviceUrl != null ? serviceUrl + "/#/push/" + details.getId() : null;
        String tail =
                "\n" + divider + (link != null ? "\n" + color(CYAN, sym(LINK) + "  View push record: " + link) : "");
        sendGitError(request, response, combined + tail);
    }
}
