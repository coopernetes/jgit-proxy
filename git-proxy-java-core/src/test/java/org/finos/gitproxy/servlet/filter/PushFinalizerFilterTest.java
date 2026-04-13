package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.git.GitClientUtils.ZERO_OID;
import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;
import static org.finos.gitproxy.servlet.GitProxyServlet.PRE_APPROVED_ATTR;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.finos.gitproxy.approval.ApprovalGateway;
import org.finos.gitproxy.approval.AutoApprovalGateway;
import org.finos.gitproxy.db.PushStoreFactory;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.provider.GitProxyProvider;
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
        details.setRepoRef(
                GitRequestDetails.RepoRef.builder().slug("/owner/repo").build());
        return details;
    }

    private GitRequestDetails pendingPushDetailsWithUser(String username, String providerName) {
        GitRequestDetails details = pendingPushDetails();
        details.setResolvedUser(username);
        GitProxyProvider provider = mock(GitProxyProvider.class);
        when(provider.getName()).thenReturn(providerName);
        when(provider.getType()).thenReturn(providerName);
        when(provider.getUri()).thenReturn(java.net.URI.create("https://" + providerName + ".com"));
        when(provider.getProviderId()).thenReturn(providerName + "/" + providerName + ".com");
        details.setProvider(provider);
        return details;
    }

    // ---- tests ----

    @Test
    void pendingPush_isBlockedPendingReview() throws Exception {
        GitRequestDetails details = pendingPushDetails();
        PushFinalizerFilter filter = new PushFinalizerFilter("http://localhost:8080", mock(ApprovalGateway.class));
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.REVIEW, details.getResult());
        assertTrue(fakeResponse.committed.get(), "Response must be committed to send git error to client");
    }

    @Test
    void rejectedPush_isLeftAlone() throws Exception {
        GitRequestDetails details = pendingPushDetails();
        details.setResult(GitRequestDetails.GitResult.REJECTED);
        PushFinalizerFilter filter = new PushFinalizerFilter("http://localhost:8080", mock(ApprovalGateway.class));
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        assertFalse(fakeResponse.committed.get(), "Finalizer must not commit response for already-rejected push");
    }

    @Test
    void errorPush_isLeftAlone() throws Exception {
        GitRequestDetails details = pendingPushDetails();
        details.setResult(GitRequestDetails.GitResult.ERROR);
        PushFinalizerFilter filter = new PushFinalizerFilter("http://localhost:8080", mock(ApprovalGateway.class));
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
        PushFinalizerFilter filter = new PushFinalizerFilter("http://localhost:8080", mock(ApprovalGateway.class));
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(req, fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.ALLOWED, details.getResult());
        assertFalse(fakeResponse.committed.get(), "Pre-approved push must not send a git error");
    }

    @Test
    void refDeletion_isAllowed() throws Exception {
        GitRequestDetails details = pendingPushDetails();
        details.setCommitTo(ZERO_OID);
        PushFinalizerFilter filter = new PushFinalizerFilter("http://localhost:8080", mock(ApprovalGateway.class));
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.ALLOWED, details.getResult());
        assertFalse(fakeResponse.committed.get(), "Ref deletion must not send a git error");
    }

    @Test
    void nullDetails_doesNotThrow() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(GIT_REQUEST_ATTR)).thenReturn(null);
        PushFinalizerFilter filter = new PushFinalizerFilter("http://localhost:8080", mock(ApprovalGateway.class));
        FakeResponse fakeResponse = new FakeResponse();

        assertDoesNotThrow(() -> filter.doHttpFilter(req, fakeResponse.mock));
    }

    @Test
    void selfCertify_perm_doesNotBypassReview() throws Exception {
        // The push-time bypass for SELF_CERTIFY was removed: self-certify is enforced exclusively in the dashboard
        // (PushController.checkReviewerIdentity). The pre-receive hook re-verifies the per-repo permission as defense
        // in depth before forwarding an approved self-review. From the proxy filter chain's perspective, every clean
        // push with no prior approval blocks pending review regardless of any SELF_CERTIFY grants.
        GitRequestDetails details = pendingPushDetailsWithUser("alice", "github");
        PushFinalizerFilter filter = new PushFinalizerFilter("http://localhost:8080", mock(ApprovalGateway.class));
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.REVIEW, details.getResult());
        assertTrue(fakeResponse.committed.get());
    }

    @Test
    void autoApprovalGateway_allowsPushWithoutBlocking() throws Exception {
        GitRequestDetails details = pendingPushDetails();
        var gateway = new AutoApprovalGateway(PushStoreFactory.inMemory());
        PushFinalizerFilter filter = new PushFinalizerFilter("http://localhost:8080", gateway);
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.ALLOWED, details.getResult());
        assertFalse(fakeResponse.committed.get(), "Auto-approval must not send a git error to the client");
    }

    // ---- credential rewriting wrapper (PushFinalizerFilter$1) ----

    @Test
    void bitbucketCredentialRewrite_authHeader_rewritten() throws Exception {
        // doFilter (not doHttpFilter) with upstreamUsername set — exercises the anonymous RequestWrapper
        GitRequestDetails details = pendingPushDetails();
        details.setUpstreamUsername("bb-username");

        var gateway = new AutoApprovalGateway(PushStoreFactory.inMemory());
        PushFinalizerFilter filter = new PushFinalizerFilter("http://localhost:8080", gateway);

        HttpServletRequest req = mockPushRequest(details);
        String originalAuth = "Basic " + Base64.getEncoder().encodeToString("user@example.com:mytoken".getBytes());
        when(req.getHeader("Authorization")).thenReturn(originalAuth);
        when(req.getHeaders(any())).thenReturn(Collections.emptyEnumeration());

        AtomicReference<HttpServletRequest> captured = new AtomicReference<>();
        FilterChain chain = (r, s) -> captured.set((HttpServletRequest) r);
        FakeResponse fakeResponse = new FakeResponse();

        filter.doFilter(req, fakeResponse.mock, chain);

        // Wrapper was passed to the chain — verify it rewrites Authorization but not other headers
        HttpServletRequest wrapper = captured.get();
        assertNotNull(wrapper);
        String rewritten = wrapper.getHeader("Authorization");
        assertTrue(rewritten.startsWith("Basic "), "Must still be Basic auth");
        String decoded = new String(Base64.getDecoder()
                .decode(rewritten.substring("Basic ".length()).trim()));
        assertTrue(decoded.startsWith("bb-username:"), "Username must be rewritten to upstream username");
        assertTrue(decoded.endsWith(":mytoken"), "Token must be preserved");

        // Non-Authorization headers delegate to super
        assertNull(wrapper.getHeader("X-Other"));

        // getHeaders path for Authorization
        assertNotNull(wrapper.getHeaders("Authorization"));
        // getHeaders for non-Authorization
        assertNotNull(wrapper.getHeaders("X-Other"));
    }
}
