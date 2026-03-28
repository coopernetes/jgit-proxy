package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyProviderServlet.GIT_REQUEST_ATTRIBUTE;
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
import org.finos.gitproxy.git.Commit;
import org.finos.gitproxy.git.Contributor;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.provider.GitHubProvider;
import org.finos.gitproxy.service.DummyUserAuthorizationService;
import org.finos.gitproxy.service.UserAuthorizationService;
import org.junit.jupiter.api.Test;

class CheckUserPushPermissionFilterTest {

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

    private static ServletInputStream emptyServletInputStream() {
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
        when(req.getAttribute(GIT_REQUEST_ATTRIBUTE)).thenReturn(details);
        when(req.getInputStream()).thenReturn(emptyServletInputStream());
        return req;
    }

    private GitRequestDetails makeDetailsWithEmail(String email) {
        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.PUSH);
        details.setRepository(GitRequestDetails.Repository.builder()
                .owner("owner")
                .name("repo")
                .slug("owner/repo")
                .build());
        if (email != null) {
            details.setCommit(Commit.builder()
                    .sha("abc123")
                    .author(Contributor.builder().name("Test").email(email).build())
                    .committer(Contributor.builder().name("Test").email(email).build())
                    .message("msg")
                    .build());
        }
        return details;
    }

    @Test
    void nullRequestDetails_returnsWithoutError() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("POST");
        when(req.getContentType()).thenReturn("application/x-git-receive-pack-request");
        when(req.getRequestURI()).thenReturn("/proxy/github.com/owner/repo.git/git-receive-pack");
        when(req.getAttribute(GIT_REQUEST_ATTRIBUTE)).thenReturn(null);
        when(req.getInputStream()).thenReturn(emptyServletInputStream());
        FakeResponse resp = new FakeResponse();

        new CheckUserPushPermissionFilter(new DummyUserAuthorizationService()).doHttpFilter(req, resp.mock);

        assertFalse(resp.committed.get());
    }

    @Test
    void nullCommit_blocks() throws Exception {
        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.PUSH);
        details.setRepository(
                GitRequestDetails.Repository.builder().slug("owner/repo").build());
        // no commit set
        FakeResponse resp = new FakeResponse();

        new CheckUserPushPermissionFilter(new DummyUserAuthorizationService())
                .doHttpFilter(mockPushRequest(details), resp.mock);

        assertTrue(resp.committed.get(), "Should block when commit/author is null");
        assertEquals(GitRequestDetails.GitResult.BLOCKED, details.getResult());
    }

    @Test
    void emptyEmail_blocks() throws Exception {
        GitRequestDetails details = makeDetailsWithEmail("");
        FakeResponse resp = new FakeResponse();

        new CheckUserPushPermissionFilter(new DummyUserAuthorizationService())
                .doHttpFilter(mockPushRequest(details), resp.mock);

        assertTrue(resp.committed.get(), "Should block when email is empty");
        assertEquals(GitRequestDetails.GitResult.BLOCKED, details.getResult());
    }

    @Test
    void userDoesNotExist_blocks() throws Exception {
        GitRequestDetails details = makeDetailsWithEmail("ghost@example.com");
        UserAuthorizationService authSvc = mock(UserAuthorizationService.class);
        when(authSvc.userExists("ghost@example.com")).thenReturn(false);
        FakeResponse resp = new FakeResponse();

        new CheckUserPushPermissionFilter(authSvc).doHttpFilter(mockPushRequest(details), resp.mock);

        assertTrue(resp.committed.get(), "Should block unknown user");
        assertEquals(GitRequestDetails.GitResult.BLOCKED, details.getResult());
    }

    @Test
    void userNotAuthorized_blocks() throws Exception {
        GitRequestDetails details = makeDetailsWithEmail("user@example.com");
        UserAuthorizationService authSvc = mock(UserAuthorizationService.class);
        when(authSvc.userExists("user@example.com")).thenReturn(true);
        when(authSvc.isUserAuthorizedToPush(eq("user@example.com"), anyString()))
                .thenReturn(false);
        FakeResponse resp = new FakeResponse();

        new CheckUserPushPermissionFilter(authSvc).doHttpFilter(mockPushRequest(details), resp.mock);

        assertTrue(resp.committed.get(), "Should block unauthorized user");
        assertEquals(GitRequestDetails.GitResult.BLOCKED, details.getResult());
    }

    @Test
    void authorizedUser_passes() throws Exception {
        GitRequestDetails details = makeDetailsWithEmail("user@example.com");
        FakeResponse resp = new FakeResponse();

        new CheckUserPushPermissionFilter(new DummyUserAuthorizationService())
                .doHttpFilter(mockPushRequest(details), resp.mock);

        assertFalse(resp.committed.get(), "Authorized user should not be blocked");
        assertEquals(GitRequestDetails.GitResult.ALLOWED, details.getResult());
    }

    @Test
    void authorizedUser_withProvider_usesProviderName() throws Exception {
        GitRequestDetails details = makeDetailsWithEmail("user@example.com");
        details.setProvider(new GitHubProvider("/proxy"));
        FakeResponse resp = new FakeResponse();

        new CheckUserPushPermissionFilter(new DummyUserAuthorizationService())
                .doHttpFilter(mockPushRequest(details), resp.mock);

        assertFalse(resp.committed.get());
    }
}
