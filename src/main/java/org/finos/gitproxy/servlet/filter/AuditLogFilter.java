package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyProviderServlet.GIT_REQUEST_ATTRIBUTE;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.git.HttpOperation;
import org.springframework.core.Ordered;

/** A default implementation of {@link AuditFilter} that logs audit messages using SLF4J logger. */
@Slf4j
public class AuditLogFilter extends AbstractGitProxyFilter implements AuditFilter {

    public static final Set<HttpOperation> DEFAULT_OPERATIONS = Set.of(HttpOperation.values());

    /** Apply audit logging to all operations by default and after all other filters. */
    public AuditLogFilter() {
        super(Ordered.LOWEST_PRECEDENCE, DEFAULT_OPERATIONS);
    }

    @Override
    public void audit(String message) {
        log.info(message);
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        // First execute the rest of the filter chain
        chain.doFilter(request, response);

        // Then perform the audit logging
        audit("" + request.getAttribute(GIT_REQUEST_ATTRIBUTE));
        var headers = Collections.list(request.getHeaderNames()).stream()
                .collect(Collectors.toMap(
                        name -> name,
                        name -> "authorization".equalsIgnoreCase(name)
                                ? "REDACTED"
                                : Collections.list(request.getHeaders(name))));
        audit("headers" + headers);
    }
}
