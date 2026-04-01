package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.finos.gitproxy.git.GitRequestDetails;

public interface AuditFilter extends GitProxyFilter {

    void audit(GitRequestDetails requestDetails);

    @Override
    default void doHttpFilter(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        var requestDetails = (GitRequestDetails) request.getAttribute(GIT_REQUEST_ATTR);
        audit(requestDetails);
    }
}
