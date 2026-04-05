package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;
import static org.finos.gitproxy.servlet.GitProxyServlet.PRE_APPROVED_ATTR;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.db.PushRecordMapper;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.model.PushRecord;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;

/**
 * A plain servlet {@link Filter} that persists push records to the {@link PushStore}. Unlike other GitProxyFilters,
 * this wraps the entire filter chain using try-finally so it always runs - even when a validation filter commits the
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
            persistIfPush(request, response);
        }
    }

    private void persistIfPush(ServletRequest request, ServletResponse response) {
        if (!(request instanceof HttpServletRequest httpRequest)) return;

        var requestDetails = (GitRequestDetails) httpRequest.getAttribute(GIT_REQUEST_ATTR);

        // Only persist push operations
        if (requestDetails == null || requestDetails.getOperation() != HttpOperation.PUSH) return;

        // For transparent-proxy re-pushes (PRE_APPROVED_ATTR is set), the upstream response is handled
        // asynchronously by GitProxyServlet.onProxyResponseSuccess/Failure, which updates the original
        // approved record directly. Skip creating a duplicate record here.
        if (Boolean.TRUE.equals(httpRequest.getAttribute(PRE_APPROVED_ATTR))) return;

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
