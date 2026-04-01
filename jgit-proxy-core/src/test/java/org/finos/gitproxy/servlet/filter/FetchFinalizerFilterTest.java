package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.junit.jupiter.api.Test;

class FetchFinalizerFilterTest {

    private HttpServletRequest mockFetchRequest(GitRequestDetails details) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(GIT_REQUEST_ATTR)).thenReturn(details);
        return req;
    }

    private GitRequestDetails pendingFetchDetails() {
        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.FETCH);
        return details;
    }

    // ---- tests ----

    @Test
    void pendingFetch_isAllowed() throws Exception {
        GitRequestDetails details = pendingFetchDetails();
        FetchFinalizerFilter filter = new FetchFinalizerFilter();

        filter.doHttpFilter(mockFetchRequest(details), mock(HttpServletResponse.class));

        assertEquals(GitRequestDetails.GitResult.ALLOWED, details.getResult());
    }

    @Test
    void rejectedFetch_isLeftAlone() throws Exception {
        GitRequestDetails details = pendingFetchDetails();
        details.setResult(GitRequestDetails.GitResult.REJECTED);
        FetchFinalizerFilter filter = new FetchFinalizerFilter();

        filter.doHttpFilter(mockFetchRequest(details), mock(HttpServletResponse.class));

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
    }

    @Test
    void blockedFetch_isLeftAlone() throws Exception {
        GitRequestDetails details = pendingFetchDetails();
        details.setResult(GitRequestDetails.GitResult.BLOCKED);
        FetchFinalizerFilter filter = new FetchFinalizerFilter();

        filter.doHttpFilter(mockFetchRequest(details), mock(HttpServletResponse.class));

        assertEquals(GitRequestDetails.GitResult.BLOCKED, details.getResult());
    }

    @Test
    void nullDetails_doesNotThrow() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(GIT_REQUEST_ATTR)).thenReturn(null);
        FetchFinalizerFilter filter = new FetchFinalizerFilter();

        assertDoesNotThrow(() -> filter.doHttpFilter(req, mock(HttpServletResponse.class)));
    }
}
