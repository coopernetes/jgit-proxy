package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.git.GitClient.AnsiColor.*;
import static org.finos.gitproxy.git.GitClient.SymbolCodes.*;
import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.git.GitClient;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;

/**
 * Filter that rejects pushes where no commits could be found in the pushed range. Two cases are distinguished:
 *
 * <ul>
 *   <li><b>Empty branch</b> — a new branch is being created but its tip is already reachable from an existing ref; no
 *       new commits were introduced.
 *   <li><b>Commit data not found</b> — a non-new-branch push produced no commit data; indicates a proxy or repository
 *       state problem.
 * </ul>
 *
 * <p>This filter short-circuits immediately via {@link #rejectAndSendError} without recording to
 * {@link org.finos.gitproxy.servlet.filter.ValidationSummaryFilter}.
 *
 * <p>Runs at order 2050, before all other content validation filters.
 */
@Slf4j
public class CheckEmptyBranchFilter extends AbstractGitProxyFilter {

    private static final int ORDER = 2050;

    public CheckEmptyBranchFilter() {
        super(ORDER, Set.of(HttpOperation.PUSH));
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var requestDetails = (GitRequestDetails) request.getAttribute(GIT_REQUEST_ATTR);
        if (requestDetails == null) {
            log.warn("GitRequestDetails not found in request attributes");
            return;
        }

        var commits = requestDetails.getPushedCommits();
        if (commits != null && !commits.isEmpty()) {
            return;
        }

        String commitFrom = requestDetails.getCommitFrom();
        boolean isNewBranch = commitFrom == null || commitFrom.matches("^0+$");

        String title;
        String message;
        if (isNewBranch) {
            title = NO_ENTRY.emoji() + "  Push Blocked — Empty Branch";
            message = "Please make a commit before pushing a new branch.";
        } else {
            title = NO_ENTRY.emoji() + "  Push Blocked — Commit Data Not Found";
            message = "Commit data not found. Please contact an administrator for support.";
        }

        log.warn("checkEmptyBranch: rejecting push — {}", message);
        rejectAndSendError(request, response, title, GitClient.format(title, message, RED, null));
    }
}
