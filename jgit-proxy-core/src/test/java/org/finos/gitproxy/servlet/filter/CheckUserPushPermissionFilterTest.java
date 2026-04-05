package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;
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
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.provider.GitHubProvider;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.service.PushIdentityResolver;
import org.finos.gitproxy.service.UserAuthorizationService;
import org.finos.gitproxy.user.UserEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CheckUserPushPermissionFilterTest {

    PushIdentityResolver resolver;
    UserAuthorizationService authService;

    @BeforeEach
    void setUp() {
        resolver = mock(PushIdentityResolver.class);
        authService = mock(UserAuthorizationService.class);
    }

    // ---- test infrastructure ----

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

    private static ServletInputStream emptyStream() {
        ByteArrayInputStream bais = new ByteArrayInputStream(new byte[0]);
        return new ServletInputStream() {
            @Override
            public int read() {
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

    private static String basicAuth(String user, String token) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + token).getBytes());
    }

    private HttpServletRequest mockRequest(GitRequestDetails details, String authHeader) throws IOException {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("POST");
        when(req.getContentType()).thenReturn("application/x-git-receive-pack-request");
        when(req.getRequestURI()).thenReturn("/proxy/github.com/owner/repo.git/git-receive-pack");
        when(req.getAttribute(GIT_REQUEST_ATTR)).thenReturn(details);
        when(req.getInputStream()).thenReturn(emptyStream());
        when(req.getHeader("Authorization")).thenReturn(authHeader);
        return req;
    }

    private GitRequestDetails pushDetails() {
        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.PUSH);
        details.setProvider(new GitHubProvider("/proxy"));
        details.setRepoRef(GitRequestDetails.RepoRef.builder()
                .owner("owner")
                .name("repo")
                .slug("owner/repo")
                .build());
        return details;
    }

    private UserEntry userEntry(String username) {
        return UserEntry.builder()
                .username(username)
                .emails(List.of())
                .scmIdentities(List.of())
                .build();
    }

    // ---- null requestDetails → no-op ----

    @Test
    void nullRequestDetails_returnsWithoutBlocking() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("POST");
        when(req.getContentType()).thenReturn("application/x-git-receive-pack-request");
        when(req.getRequestURI()).thenReturn("/proxy/github.com/owner/repo.git/git-receive-pack");
        when(req.getAttribute(GIT_REQUEST_ATTR)).thenReturn(null);
        when(req.getInputStream()).thenReturn(emptyStream());
        FakeResponse resp = new FakeResponse();

        new CheckUserPushPermissionFilter(resolver, authService).doHttpFilter(req, resp.mock);

        assertFalse(resp.committed.get());
        verifyNoInteractions(resolver, authService);
    }

    // ---- null resolver (open mode) → skip check ----

    @Test
    void nullResolver_skipsCheck_doesNotBlock() throws Exception {
        GitRequestDetails details = pushDetails();
        FakeResponse resp = new FakeResponse();

        new CheckUserPushPermissionFilter(null, authService)
                .doHttpFilter(mockRequest(details, basicAuth("anyone", "token")), resp.mock);

        assertFalse(resp.committed.get(), "Open mode (null resolver) must not block");
        assertEquals(GitRequestDetails.GitResult.PENDING, details.getResult());
        verifyNoInteractions(authService);
    }

    // ---- resolver returns empty → reject ----

    @Test
    void resolverReturnsEmpty_blocks() throws Exception {
        GitRequestDetails details = pushDetails();
        when(resolver.resolve(any(GitProxyProvider.class), eq("ghost"), eq("tok")))
                .thenReturn(Optional.empty());
        FakeResponse resp = new FakeResponse();

        new CheckUserPushPermissionFilter(resolver, authService)
                .doHttpFilter(mockRequest(details, basicAuth("ghost", "tok")), resp.mock);

        assertTrue(resp.committed.get(), "Should block unresolved user");
        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        verifyNoInteractions(authService);
    }

    // ---- resolver resolves but authorization denies → reject ----

    @Test
    void resolvedButNotAuthorized_blocks() throws Exception {
        GitRequestDetails details = pushDetails();
        details.setProvider(new GitHubProvider("/proxy"));
        when(resolver.resolve(any(GitProxyProvider.class), eq("corp"), eq("tok")))
                .thenReturn(Optional.of(userEntry("alice")));
        when(authService.isUserAuthorizedToPush(eq("alice"), anyString())).thenReturn(false);
        FakeResponse resp = new FakeResponse();

        new CheckUserPushPermissionFilter(resolver, authService)
                .doHttpFilter(mockRequest(details, basicAuth("corp", "tok")), resp.mock);

        assertTrue(resp.committed.get(), "Should block unauthorized user");
        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
    }

    // ---- authorized user passes ----

    @Test
    void resolvedAndAuthorized_passes() throws Exception {
        GitRequestDetails details = pushDetails();
        when(resolver.resolve(any(GitProxyProvider.class), eq("corp"), eq("tok")))
                .thenReturn(Optional.of(userEntry("alice")));
        when(authService.isUserAuthorizedToPush(eq("alice"), anyString())).thenReturn(true);
        FakeResponse resp = new FakeResponse();

        new CheckUserPushPermissionFilter(resolver, authService)
                .doHttpFilter(mockRequest(details, basicAuth("corp", "tok")), resp.mock);

        assertFalse(resp.committed.get(), "Authorized user must not be blocked");
        assertEquals(GitRequestDetails.GitResult.PENDING, details.getResult());
    }

    // ---- no Authorization header → resolver called with null username ----

    @Test
    void noAuthHeader_resolverCalledWithNullUsername() throws Exception {
        GitRequestDetails details = pushDetails();
        when(resolver.resolve(any(GitProxyProvider.class), isNull(), isNull())).thenReturn(Optional.empty());
        FakeResponse resp = new FakeResponse();

        new CheckUserPushPermissionFilter(resolver, authService).doHttpFilter(mockRequest(details, null), resp.mock);

        verify(resolver).resolve(any(GitProxyProvider.class), isNull(), isNull());
        assertTrue(resp.committed.get());
    }

    // ---- provider instance is passed to resolver ----

    @Test
    void provider_isPassedToResolver() throws Exception {
        GitRequestDetails details = pushDetails();
        GitProxyProvider github = new GitHubProvider("/proxy");
        details.setProvider(github);
        when(resolver.resolve(eq(github), eq("corp"), eq("tok"))).thenReturn(Optional.of(userEntry("alice")));
        when(authService.isUserAuthorizedToPush(eq("alice"), anyString())).thenReturn(true);
        FakeResponse resp = new FakeResponse();

        new CheckUserPushPermissionFilter(resolver, authService)
                .doHttpFilter(mockRequest(details, basicAuth("corp", "tok")), resp.mock);

        verify(resolver).resolve(eq(github), eq("corp"), eq("tok"));
        assertFalse(resp.committed.get());
    }

    // ---- token with colon in password is handled correctly ----

    @Test
    void tokenWithColon_extractedCorrectly() throws Exception {
        // GitHub PATs can contain colons; only the first colon is the user:pass separator
        String tokenWithColon = "ghp_abc:xyz";
        GitRequestDetails details = pushDetails();
        when(resolver.resolve(any(GitProxyProvider.class), eq("user"), eq(tokenWithColon)))
                .thenReturn(Optional.of(userEntry("user")));
        when(authService.isUserAuthorizedToPush(anyString(), anyString())).thenReturn(true);
        FakeResponse resp = new FakeResponse();

        new CheckUserPushPermissionFilter(resolver, authService)
                .doHttpFilter(mockRequest(details, basicAuth("user", tokenWithColon)), resp.mock);

        verify(resolver).resolve(any(GitProxyProvider.class), eq("user"), eq(tokenWithColon));
        assertFalse(resp.committed.get());
    }
}
