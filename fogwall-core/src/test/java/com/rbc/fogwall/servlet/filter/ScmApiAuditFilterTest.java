package com.rbc.fogwall.servlet.filter;

import static com.rbc.fogwall.servlet.ScmApiRequestContext.SCM_API_REQUEST_ATTR;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rbc.fogwall.db.ScmApiActionStore;
import com.rbc.fogwall.db.model.ScmApiActionRecord;
import com.rbc.fogwall.db.model.ScmApiActionStatus;
import com.rbc.fogwall.servlet.ScmApiRequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScmApiAuditFilterTest {

    @Test
    void mutationContext_writesRecordAfterChain() throws Exception {
        ScmApiActionStore store = mock(ScmApiActionStore.class);
        ScmApiRequestContext context = new ScmApiRequestContext();
        context.setProvider("github");
        context.setResolvedUser("alice");
        context.setMutationField("createIssue");
        context.setNodeId("R_1");
        context.setRepoOwner("acme");
        context.setRepoName("widgets");
        context.setStatus(ScmApiActionStatus.FORWARDED);

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(SCM_API_REQUEST_ATTR)).thenReturn(context);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        new ScmApiAuditFilter(store).doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        ArgumentCaptor<ScmApiActionRecord> captor = ArgumentCaptor.forClass(ScmApiActionRecord.class);
        verify(store).save(captor.capture());
        assertEquals("github", captor.getValue().getProvider());
        assertEquals("createIssue", captor.getValue().getMutationField());
        assertEquals(ScmApiActionStatus.FORWARDED, captor.getValue().getStatus());
    }

    @Test
    void readContext_noMutationField_doesNotWriteRecord() throws Exception {
        ScmApiActionStore store = mock(ScmApiActionStore.class);
        ScmApiRequestContext context = new ScmApiRequestContext();
        context.setProvider("github");
        context.setResolvedUser("alice");
        // mutationField left null: a read

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(SCM_API_REQUEST_ATTR)).thenReturn(context);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        new ScmApiAuditFilter(store).doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        verifyNoInteractions(store);
    }

    @Test
    void noContextAttribute_doesNotWriteRecord() throws Exception {
        ScmApiActionStore store = mock(ScmApiActionStore.class);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(SCM_API_REQUEST_ATTR)).thenReturn(null);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        new ScmApiAuditFilter(store).doFilter(req, resp, chain);

        verifyNoInteractions(store);
    }

    @Test
    void chainThrows_stillWritesRecord_thenRethrows() throws Exception {
        ScmApiActionStore store = mock(ScmApiActionStore.class);
        ScmApiRequestContext context = new ScmApiRequestContext();
        context.setMutationField("createIssue");
        context.setStatus(ScmApiActionStatus.ERROR);
        context.setReason("boom");

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(SCM_API_REQUEST_ATTR)).thenReturn(context);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        doThrow(new RuntimeException("downstream failure"))
                .when(chain)
                .doFilter(any(ServletRequest.class), any(ServletResponse.class));

        assertThrows(RuntimeException.class, () -> new ScmApiAuditFilter(store).doFilter(req, resp, chain));

        verify(store).save(any(ScmApiActionRecord.class));
    }

    @Test
    void storeThrows_doesNotPropagate() throws Exception {
        ScmApiActionStore store = mock(ScmApiActionStore.class);
        doThrow(new RuntimeException("db down")).when(store).save(any());
        ScmApiRequestContext context = new ScmApiRequestContext();
        context.setMutationField("createIssue");
        context.setStatus(ScmApiActionStatus.FORWARDED);

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(SCM_API_REQUEST_ATTR)).thenReturn(context);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        assertDoesNotThrow(() -> new ScmApiAuditFilter(store).doFilter(req, resp, chain));
    }

    /**
     * An endpoint matching no allowlist rule is refused before there is an operation to name, and that refusal is what
     * shows an operator a CLI started calling something the allowlist does not know.
     */
    @Test
    void deniedAuthenticatedCallerWithNoMutationField_writesRecord() throws Exception {
        ScmApiActionStore store = mock(ScmApiActionStore.class);
        ScmApiRequestContext context = new ScmApiRequestContext();
        context.setProvider("gitea");
        context.setResolvedUser("alice");
        context.setStatus(ScmApiActionStatus.DENIED);
        context.setReason("Operation 'POST /repos/acme/widgets/pulls/1/reviews' is not allowlisted");

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(SCM_API_REQUEST_ATTR)).thenReturn(context);

        new ScmApiAuditFilter(store).doFilter(req, mock(HttpServletResponse.class), mock(FilterChain.class));

        ArgumentCaptor<ScmApiActionRecord> captor = ArgumentCaptor.forClass(ScmApiActionRecord.class);
        verify(store).save(captor.capture());
        assertEquals(ScmApiActionStatus.DENIED, captor.getValue().getStatus());
        assertNull(captor.getValue().getMutationField(), "there was no operation to name");
        assertTrue(captor.getValue().getReason().contains("not allowlisted"));
    }

    /**
     * Refused before authentication, so no row. Matches the push path, where a request rejected before it parses as a
     * push writes no push record — and it is the only case an unauthenticated caller could repeat at will.
     */
    @Test
    void deniedBeforeAuthentication_doesNotWriteRecord() throws Exception {
        ScmApiActionStore store = mock(ScmApiActionStore.class);
        ScmApiRequestContext context = new ScmApiRequestContext();
        context.setProvider("gitea");
        context.setStatus(ScmApiActionStatus.DENIED);
        context.setReason("Unrecognised client");

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(SCM_API_REQUEST_ATTR)).thenReturn(context);

        new ScmApiAuditFilter(store).doFilter(req, mock(HttpServletResponse.class), mock(FilterChain.class));

        verify(store, never()).save(any());
    }

    @Test
    void forwardedReadByAuthenticatedCaller_doesNotWriteRecord() throws Exception {
        ScmApiActionStore store = mock(ScmApiActionStore.class);
        ScmApiRequestContext context = new ScmApiRequestContext();
        context.setProvider("gitea");
        context.setResolvedUser("alice");
        context.setStatus(ScmApiActionStatus.FORWARDED);

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(SCM_API_REQUEST_ATTR)).thenReturn(context);

        new ScmApiAuditFilter(store).doFilter(req, mock(HttpServletResponse.class), mock(FilterChain.class));

        verify(store, never()).save(any());
    }

    /**
     * The header is caller-controlled and the column is VARCHAR(512): an over-long value made the insert throw, and the
     * audit write is wrapped in a catch, so the record vanished — for forwarded mutations too.
     */
    @Test
    void overlongUserAgent_isCappedToTheColumnWidth() throws Exception {
        ScmApiRequestContext context = new ScmApiRequestContext();
        context.setProvider("github");
        context.setResolvedUser("alice");
        context.setMutationField("createIssue");
        context.setStatus(ScmApiActionStatus.FORWARDED);
        context.setUserAgent("x".repeat(900));

        assertEquals(512, saved(context).getUserAgent().length());
    }

    /** A denial names what was refused, and what was refused is whatever the caller sent. */
    @Test
    void overlongReason_isCapped() throws Exception {
        ScmApiRequestContext context = new ScmApiRequestContext();
        context.setProvider("gitea");
        context.setResolvedUser("alice");
        context.setStatus(ScmApiActionStatus.DENIED);
        context.setReason("Operation 'POST /repos/" + "a".repeat(9000) + "' is not allowlisted");

        String reason = saved(context).getReason();
        assertEquals(2000, reason.length());
        assertTrue(reason.endsWith("\u2026"), "truncation is marked so a reader can tell: " + reason.substring(1990));
    }

    /**
     * The node ID is copied out of the caller's own GraphQL variables, and owner/repo are decoded out of the request
     * path — all three reach fixed-width columns, so an over-long value would lose the denial row that names it.
     */
    @Test
    void overlongTargetValues_areCappedToTheirColumnWidths() throws Exception {
        ScmApiRequestContext context = new ScmApiRequestContext();
        context.setProvider("github");
        context.setResolvedUser("alice");
        context.setMutationField("createIssue" + "X".repeat(500));
        context.setNodeId("R_" + "k".repeat(900));
        context.setRepoOwner("a".repeat(900));
        context.setRepoName("b".repeat(900));
        context.setStatus(ScmApiActionStatus.DENIED);

        ScmApiActionRecord record = saved(context);
        assertEquals(100, record.getMutationField().length());
        assertEquals(255, record.getNodeId().length());
        assertEquals(255, record.getRepoOwner().length());
        assertEquals(255, record.getRepoName().length());
    }

    @Test
    void shortValues_arePassedThroughUnchanged() throws Exception {
        ScmApiRequestContext context = new ScmApiRequestContext();
        context.setProvider("github");
        context.setResolvedUser("alice");
        context.setMutationField("createIssue");
        context.setStatus(ScmApiActionStatus.FORWARDED);
        context.setUserAgent("GitHub CLI 2.98.0");
        context.setReason("all good");

        ScmApiActionRecord record = saved(context);
        assertEquals("GitHub CLI 2.98.0", record.getUserAgent());
        assertEquals("all good", record.getReason());
    }

    private static ScmApiActionRecord saved(ScmApiRequestContext context) throws Exception {
        ScmApiActionStore store = mock(ScmApiActionStore.class);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(SCM_API_REQUEST_ATTR)).thenReturn(context);

        new ScmApiAuditFilter(store).doFilter(req, mock(HttpServletResponse.class), mock(FilterChain.class));

        ArgumentCaptor<ScmApiActionRecord> captor = ArgumentCaptor.forClass(ScmApiActionRecord.class);
        verify(store).save(captor.capture());
        return captor.getValue();
    }
}
