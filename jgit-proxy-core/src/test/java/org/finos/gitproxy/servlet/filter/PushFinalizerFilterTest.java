package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;
import static org.finos.gitproxy.servlet.GitProxyServlet.PRE_APPROVED_ATTR;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.junit.jupiter.api.Test;

class PushFinalizerFilterTest {

    private static class FakeResponse {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final AtomicBoolean committed = new AtomicBoolean(false);
        final HttpServletResponse mock;

        FakeResponse() throws IOException {
            mock = mock(HttpServletResponse.class);
            when(mock.getOutputStream()).thenReturn(new ServletOutputStream() {
                @Override
                public void write(int b) {
                    body.write(b);
                    committed.set(true);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    body.write(b, off, len);
                    committed.set(true);
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener l) {}
            });
            when(mock.isCommitted()).thenAnswer(inv -> committed.get());
        }
    }

    private static ServletInputStream emptyInputStream() {
        ByteArrayInputStream bais = new ByteArrayInputStream(new byte[0]);
        return new ServletInputStream() {
            @Override
            public int read() throws IOException {
                return bais.read();
            }

            @Override
            public boolean isFinished() {
                return bais.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener l) {}
        };
    }

    private HttpServletRequest mockPushRequest(GitRequestDetails details) throws IOException {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("POST");
        when(req.getContentType()).thenReturn("application/x-git-receive-pack-request");
        when(req.getRequestURI()).thenReturn("/proxy/github.com/owner/repo.git/git-receive-pack");
        when(req.getAttribute(GIT_REQUEST_ATTR)).thenReturn(details);
        when(req.getInputStream()).thenReturn(emptyInputStream());
        return req;
    }

    private GitRequestDetails pendingPushDetails() {
        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.PUSH);
        details.setCommitTo("abc123");
        details.setRepository(GitRequestDetails.Repository.builder().slug("owner/repo").build());
        return details;
    }

    // ---- tests ----

    @Test
    void pendingPush_isBlockedPendingReview() throws Exception {
        GitRequestDetails details = pendingPushDetails();
        PushFinalizerFilter filter = new PushFinalizerFilter("http://localhost:8080");
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.BLOCKED, details.getResult());
        assertTrue(fakeResponse.committed.get(), "Response must be committed to send git error to client");
    }

    @Test
    void rejectedPush_isLeftAlone() throws Exception {
        GitRequestDetails details = pendingPushDetails();
        details.setResult(GitRequestDetails.GitResult.REJECTED);
        PushFinalizerFilter filter = new PushFinalizerFilter("http://localhost:8080");
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        assertFalse(fakeResponse.committed.get(), "Finalizer must not commit response for already-rejected push");
    }

    @Test
    void errorPush_isLeftAlone() throws Exception {
        GitRequestDetails details = pendingPushDetails();
        details.setResult(GitRequestDetails.GitResult.ERROR);
        PushFinalizerFilter filter = new PushFinalizerFilter("http://localhost:8080");
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.ERROR, details.getResult());
        assertFalse(fakeResponse.committed.get());
    }

    @Test
    void preApprovedPush_isAllowed() throws Exception {
        GitRequestDetails details = pendingPushDetails();
        HttpServletRequest req = mockPushRequest(details);
        when(req.getAttribute(PRE_APPROVED_ATTR)).thenReturn(Boolean.TRUE);
        PushFinalizerFilter filter = new PushFinalizerFilter("http://localhost:8080");
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(req, fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.ALLOWED, details.getResult());
        assertFalse(fakeResponse.committed.get(), "Pre-approved push must not send a git error");
    }

    @Test
    void refDeletion_isAllowed() throws Exception {
        GitRequestDetails details = pendingPushDetails();
        details.setCommitTo("0000000000000000000000000000000000000000");
        PushFinalizerFilter filter = new PushFinalizerFilter("http://localhost:8080");
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.ALLOWED, details.getResult());
        assertFalse(fakeResponse.committed.get(), "Ref deletion must not send a git error");
    }

    @Test
    void nullDetails_doesNotThrow() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(GIT_REQUEST_ATTR)).thenReturn(null);
        PushFinalizerFilter filter = new PushFinalizerFilter("http://localhost:8080");
        FakeResponse fakeResponse = new FakeResponse();

        assertDoesNotThrow(() -> filter.doHttpFilter(req, fakeResponse.mock));
    }
}
