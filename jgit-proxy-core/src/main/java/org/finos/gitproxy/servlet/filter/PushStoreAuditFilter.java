package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyProviderServlet.GIT_REQUEST_ATTRIBUTE;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.http.server.GitSmartHttpTools;
import org.finos.gitproxy.db.PushRecordMapper;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.model.PushRecord;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;

/**
 * A plain servlet {@link Filter} that persists push records to the {@link PushStore}. Unlike other GitProxyFilters,
 * this wraps the entire filter chain using try-finally so it always runs — even when a validation filter commits the
 * response early (e.g., via {@code sendGitError}).
 *
 * <p>This filter should be registered BEFORE all other filters so its {@code finally} block executes after them.
 */
@Slf4j
@RequiredArgsConstructor
public class PushStoreAuditFilter implements Filter {

    private final PushStore pushStore;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            chain.doFilter(request, response);
        } finally {
            persistIfPush(request);
        }
    }

    private void persistIfPush(ServletRequest request) {
        if (!(request instanceof HttpServletRequest httpRequest)) return;

        // Only persist receive-pack (push) operations
        if (!GitSmartHttpTools.isReceivePack(httpRequest)) return;

        var requestDetails = (GitRequestDetails) httpRequest.getAttribute(GIT_REQUEST_ATTRIBUTE);
        if (requestDetails == null) return;

        try {
            PushRecord record = PushRecordMapper.fromRequestDetails(requestDetails);
            pushStore.save(record);
            log.info(
                    "Persisted push record: id={}, repo={}, status={}",
                    record.getId(),
                    record.getUrl(),
                    record.getStatus());
        } catch (Exception e) {
            log.error("Failed to persist push record for request {}", requestDetails.getId(), e);
        }
    }
}
