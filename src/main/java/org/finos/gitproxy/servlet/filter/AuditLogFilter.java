package org.finos.gitproxy.servlet.filter;

import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.git.GitRequestDetails;
import org.springframework.core.Ordered;

/** A default implementation of {@link AuditFilter} that logs audit messages using SLF4J logger. */
@Slf4j
public class AuditLogFilter extends AbstractGitProxyFilter implements AuditFilter {

    /** Apply audit logging to all operations by default and after all other filters. */
    public AuditLogFilter() {
        super(Ordered.LOWEST_PRECEDENCE);
    }

    @Override
    public void audit(GitRequestDetails requestDetails) {
        log.info(
                "Result={},Reason={},Provider={},Repository={},Operation={}",
                requestDetails.getResult(),
                requestDetails.getReason(),
                requestDetails.getProvider().getName(),
                requestDetails.getRepository(),
                requestDetails.getOperation());
    }
}
