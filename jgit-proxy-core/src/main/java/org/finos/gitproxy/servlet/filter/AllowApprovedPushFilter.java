package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyProviderServlet.GIT_REQUEST_ATTRIBUTE;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.model.PushQuery;
import org.finos.gitproxy.db.model.PushRecord;
import org.finos.gitproxy.db.model.PushStatus;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;

/**
 * Filter that:
 *
 * <ol>
 *   <li>Sets the {@code gitproxy.serviceUrl} request attribute so downstream filters can include the dashboard link in
 *       block error messages.
 *   <li>For PUSH operations, checks whether a prior approved record exists for the same {@code commitTo} + branch +
 *       repo (transparent-proxy re-push flow). If found, sets the {@code gitproxy.preApproved} attribute to
 *       short-circuit remaining validation filters.
 * </ol>
 *
 * <p>Must be registered at order 499 — after {@code ParseGitRequestFilter} (which populates {@link GitRequestDetails})
 * but before content validation filters (order 2000+).
 */
@Slf4j
public class AllowApprovedPushFilter extends AbstractGitProxyFilter {

    private static final String PRE_APPROVED_ATTR = "gitproxy.preApproved";
    private static final String SERVICE_URL_ATTR = "gitproxy.serviceUrl";

    private final PushStore pushStore;
    private final String serviceUrl;

    public AllowApprovedPushFilter(PushStore pushStore, String serviceUrl) {
        super(499);
        this.pushStore = pushStore;
        this.serviceUrl = serviceUrl;
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Always stamp the service URL so block messages can include the link
        request.setAttribute(SERVICE_URL_ATTR, serviceUrl);

        // Only check for prior approval on PUSH operations
        if (determineOperation(request) != HttpOperation.PUSH) {
            return;
        }

        var details = (GitRequestDetails) request.getAttribute(GIT_REQUEST_ATTRIBUTE);
        if (details == null || details.getCommit() == null) {
            return;
        }

        String commitTo = details.getCommitTo();
        String branch = details.getBranch();
        String repoName =
                details.getRepository() != null ? details.getRepository().getName() : null;

        if (commitTo == null || commitTo.isBlank()) {
            return;
        }

        // Look up whether this exact commit was already approved
        List<PushRecord> approved = pushStore.find(PushQuery.builder()
                .commitTo(commitTo)
                .branch(branch)
                .repoName(repoName)
                .status(PushStatus.APPROVED)
                .limit(1)
                .build());

        if (!approved.isEmpty()) {
            log.info(
                    "Push {} (commitTo={}) was previously approved — allowing re-push through",
                    approved.get(0).getId(),
                    commitTo);
            request.setAttribute(PRE_APPROVED_ATTR, Boolean.TRUE);
        }
    }
}
