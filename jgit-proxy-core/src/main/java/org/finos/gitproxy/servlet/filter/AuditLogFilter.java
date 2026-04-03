package org.finos.gitproxy.servlet.filter;

import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.git.GitRequestDetails;

/** A default implementation of {@link AuditFilter} that logs audit messages using SLF4J logger. */
@Slf4j
public class AuditLogFilter extends AbstractGitProxyFilter implements AuditFilter {

    /** Apply audit logging to all operations by default and after all other filters. */
    public AuditLogFilter() {
        super(Integer.MAX_VALUE);
    }

    @Override
    public void audit(GitRequestDetails requestDetails) {
        log.info(
                "Result={},Reason={},Provider={},Repository={},Operation={}",
                requestDetails.getResult(),
                requestDetails.getReason(),
                requestDetails.getProvider().getName(),
                requestDetails.getRepoRef(),
                requestDetails.getOperation());
    }
}
