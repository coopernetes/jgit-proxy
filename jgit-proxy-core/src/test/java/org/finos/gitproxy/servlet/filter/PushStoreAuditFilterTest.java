package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.model.PushRecord;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PushStoreAuditFilterTest {

    PushStore pushStore;
    PushStoreAuditFilter filter;

    @BeforeEach
    void setUp() {
        pushStore = mock(PushStore.class);
        filter = new PushStoreAuditFilter(pushStore);
    }

    private HttpServletRequest pushRequest(GitRequestDetails details) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(GIT_REQUEST_ATTR)).thenReturn(details);
        return req;
    }

    private GitRequestDetails pushDetails() {
        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.PUSH);
        details.setRepoRef(GitRequestDetails.RepoRef.builder()
                .owner("owner")
                .name("repo")
                .slug("owner/repo")
                .build());
        return details;
    }

    // ---- chain always runs ----

    @Test
    void chainAlwaysRuns() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        HttpServletRequest req = pushRequest(pushDetails());
        HttpServletResponse resp = mock(HttpServletResponse.class);

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
    }

    // ---- finally block persists even when chain throws ----

    @Test
    void persistsEvenWhenChainThrows() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        doThrow(new RuntimeException("chain error")).when(chain).doFilter(any(), any());

        HttpServletRequest req = pushRequest(pushDetails());
        HttpServletResponse resp = mock(HttpServletResponse.class);

        assertThrows(RuntimeException.class, () -> filter.doFilter(req, resp, chain));

        verify(pushStore).save(any(PushRecord.class));
    }

    // ---- push operation is persisted ----

    @Test
    void pushOperation_persists() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        HttpServletRequest req = pushRequest(pushDetails());

        filter.doFilter(req, mock(HttpServletResponse.class), chain);

        verify(pushStore).save(any(PushRecord.class));
    }

    // ---- fetch operation is NOT persisted ----

    @Test
    void fetchOperation_notPersisted() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.FETCH);
        HttpServletRequest req = pushRequest(details);

        filter.doFilter(req, mock(HttpServletResponse.class), chain);

        verifyNoInteractions(pushStore);
    }

    // ---- null requestDetails is not persisted ----

    @Test
    void nullDetails_notPersisted() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(GIT_REQUEST_ATTR)).thenReturn(null);

        filter.doFilter(req, mock(HttpServletResponse.class), chain);

        verifyNoInteractions(pushStore);
    }

    // ---- non-HTTP request is silently skipped ----

    @Test
    void nonHttpRequest_skipped() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        ServletRequest req = mock(ServletRequest.class);
        ServletResponse resp = mock(ServletResponse.class);

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        verifyNoInteractions(pushStore);
    }

    // ---- store failure does not propagate (audit must not break pushes) ----

    @Test
    void storeFailure_doesNotPropagate() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        doThrow(new RuntimeException("db down")).when(pushStore).save(any());

        HttpServletRequest req = pushRequest(pushDetails());

        assertDoesNotThrow(() -> filter.doFilter(req, mock(HttpServletResponse.class), chain));
    }
}
