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
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.git.Commit;
import org.finos.gitproxy.git.Contributor;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.provider.GitHubProvider;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.service.PushIdentityResolver;
import org.finos.gitproxy.user.UserEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IdentityVerificationFilterTest {

    PushIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = mock(PushIdentityResolver.class);
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

    private GitRequestDetails pushDetailsWithCommits(List<Commit> commits) {
        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.PUSH);
        details.setProvider(new GitHubProvider("/proxy"));
        details.setRepoRef(GitRequestDetails.RepoRef.builder()
                .owner("owner")
                .name("repo")
                .slug("/owner/repo")
                .build());
        details.getPushedCommits().addAll(commits);
        return details;
    }

    private static Commit commitWith(String sha, String authorEmail) {
        return Commit.builder()
                .sha(sha)
                .author(Contributor.builder().name("Dev").email(authorEmail).build())
                .committer(Contributor.builder().name("Dev").email(authorEmail).build())
                .build();
    }

    private static UserEntry aliceEntry() {
        return UserEntry.builder()
                .username("alice")
                .emails(List.of("alice@example.com"))
                .scmIdentities(List.of())
                .build();
    }

    // ---- mode=off → no-op ----

    @Test
    void modeOff_doesNothing() throws Exception {
        GitRequestDetails details = pushDetailsWithCommits(List.of(commitWith("abc1234", "other@example.com")));
        when(resolver.resolve(any(GitProxyProvider.class), anyString(), anyString()))
                .thenReturn(Optional.of(aliceEntry()));
        FakeResponse resp = new FakeResponse();

        new IdentityVerificationFilter(resolver, CommitConfig.IdentityVerificationMode.OFF)
                .doHttpFilter(mockRequest(details, basicAuth("alice-git", "token")), resp.mock);

        assertFalse(resp.committed.get());
        assertEquals(GitRequestDetails.GitResult.PENDING, details.getResult());
        verifyNoInteractions(resolver);
    }

    // ---- null resolver (open mode) → no-op ----

    @Test
    void nullResolver_doesNothing() throws Exception {
        GitRequestDetails details = pushDetailsWithCommits(List.of(commitWith("abc1234", "other@example.com")));
        FakeResponse resp = new FakeResponse();

        new IdentityVerificationFilter(null, CommitConfig.IdentityVerificationMode.STRICT)
                .doHttpFilter(mockRequest(details, basicAuth("alice-git", "token")), resp.mock);

        assertFalse(resp.committed.get());
        assertEquals(GitRequestDetails.GitResult.PENDING, details.getResult());
    }

    // ---- no commits → no-op ----

    @Test
    void noCommits_doesNothing() throws Exception {
        GitRequestDetails details = pushDetailsWithCommits(List.of());
        FakeResponse resp = new FakeResponse();

        new IdentityVerificationFilter(resolver, CommitConfig.IdentityVerificationMode.STRICT)
                .doHttpFilter(mockRequest(details, basicAuth("alice-git", "token")), resp.mock);

        assertFalse(resp.committed.get());
        verifyNoInteractions(resolver);
    }

    // ---- emails match → passes ----

    @Test
    void emailsMatch_passes() throws Exception {
        GitRequestDetails details = pushDetailsWithCommits(List.of(commitWith("abc1234", "alice@example.com")));
        when(resolver.resolve(any(GitProxyProvider.class), eq("alice-git"), eq("token")))
                .thenReturn(Optional.of(aliceEntry()));
        FakeResponse resp = new FakeResponse();

        new IdentityVerificationFilter(resolver, CommitConfig.IdentityVerificationMode.STRICT)
                .doHttpFilter(mockRequest(details, basicAuth("alice-git", "token")), resp.mock);

        assertFalse(resp.committed.get());
        assertEquals(GitRequestDetails.GitResult.PENDING, details.getResult());
    }

    // ---- strict mode + email mismatch → records issue (REJECTED) ----

    @Test
    void strictMode_emailMismatch_recordsIssue() throws Exception {
        GitRequestDetails details = pushDetailsWithCommits(List.of(commitWith("abc1234", "other@example.com")));
        when(resolver.resolve(any(GitProxyProvider.class), eq("alice-git"), eq("token")))
                .thenReturn(Optional.of(aliceEntry()));
        FakeResponse resp = new FakeResponse();

        new IdentityVerificationFilter(resolver, CommitConfig.IdentityVerificationMode.STRICT)
                .doHttpFilter(mockRequest(details, basicAuth("alice-git", "token")), resp.mock);

        // recordIssue sets REJECTED but does not commit the response (ValidationSummaryFilter does that)
        assertFalse(resp.committed.get());
        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        assertFalse(details.getSteps().isEmpty());
        assertEquals("identityVerification", details.getSteps().get(0).getStepName());
    }

    // ---- warn mode + email mismatch → passes without recording an issue ----

    @Test
    void warnMode_emailMismatch_passes() throws Exception {
        GitRequestDetails details = pushDetailsWithCommits(List.of(commitWith("abc1234", "other@example.com")));
        when(resolver.resolve(any(GitProxyProvider.class), eq("alice-git"), eq("token")))
                .thenReturn(Optional.of(aliceEntry()));
        FakeResponse resp = new FakeResponse();

        new IdentityVerificationFilter(resolver, CommitConfig.IdentityVerificationMode.WARN)
                .doHttpFilter(mockRequest(details, basicAuth("alice-git", "token")), resp.mock);

        assertFalse(resp.committed.get());
        assertEquals(GitRequestDetails.GitResult.PENDING, details.getResult(), "WARN mode must not reject push");
        // WARN mode records a PASS step with violation details in content (for the amber dashboard badge)
        assertFalse(details.getSteps().isEmpty(), "WARN mode should record a step");
        var step = details.getSteps().get(0);
        assertEquals("identityVerification", step.getStepName());
        assertEquals(org.finos.gitproxy.db.model.StepStatus.PASS, step.getStatus());
        assertNotNull(step.getContent(), "WARN mode step should carry violation details in content");
    }

    // ---- resolver returns empty → skip (CheckUserPushPermissionFilter handles "not registered") ----

    @Test
    void resolverEmpty_skips() throws Exception {
        GitRequestDetails details = pushDetailsWithCommits(List.of(commitWith("abc1234", "someone@example.com")));
        when(resolver.resolve(any(GitProxyProvider.class), anyString(), anyString()))
                .thenReturn(Optional.empty());
        FakeResponse resp = new FakeResponse();

        new IdentityVerificationFilter(resolver, CommitConfig.IdentityVerificationMode.STRICT)
                .doHttpFilter(mockRequest(details, basicAuth("unknown", "token")), resp.mock);

        assertFalse(resp.committed.get());
        assertEquals(GitRequestDetails.GitResult.PENDING, details.getResult());
    }

    // ---- committer-only mismatch (different from author) is also flagged in strict mode ----

    @Test
    void strictMode_committerMismatch_recordsIssue() throws Exception {
        Commit commit = Commit.builder()
                .sha("abc1234")
                .author(Contributor.builder()
                        .name("Alice")
                        .email("alice@example.com")
                        .build())
                .committer(Contributor.builder()
                        .name("Other")
                        .email("other@example.com")
                        .build())
                .build();
        GitRequestDetails details = pushDetailsWithCommits(List.of(commit));
        when(resolver.resolve(any(GitProxyProvider.class), eq("alice-git"), eq("token")))
                .thenReturn(Optional.of(aliceEntry()));
        FakeResponse resp = new FakeResponse();

        new IdentityVerificationFilter(resolver, CommitConfig.IdentityVerificationMode.STRICT)
                .doHttpFilter(mockRequest(details, basicAuth("alice-git", "token")), resp.mock);

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
    }
}
