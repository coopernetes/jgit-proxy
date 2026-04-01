package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;

// A filter that sets a git request to be ALLOWED for fetch requests so long as the request
// has passed through all applicable fetch filters unmodified (ie. request is still in PENDING
// initial state & not a mutated result such as ERROR, REJECTED or BLOCKED)
public class FetchFinalizerFilter extends AbstractGitProxyFilter {

    private static final int ORDER = 5000;

    public FetchFinalizerFilter() {
        super(ORDER, Set.of(HttpOperation.FETCH));
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        var details = (GitRequestDetails) request.getAttribute(GIT_REQUEST_ATTR);
        if (details != null && details.getResult() == GitRequestDetails.GitResult.PENDING) {
            details.setResult(GitRequestDetails.GitResult.ALLOWED);
        }
    }
}
