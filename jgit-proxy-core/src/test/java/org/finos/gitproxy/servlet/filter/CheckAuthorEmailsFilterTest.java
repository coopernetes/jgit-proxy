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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.db.model.StepStatus;
import org.finos.gitproxy.git.Commit;
import org.finos.gitproxy.git.Contributor;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.junit.jupiter.api.Test;

class CheckAuthorEmailsFilterTest {

    // ---- helpers ----

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
        when(req.getAttribute(GIT_REQUEST_ATTR)).thenReturn(details);
        when(req.getInputStream()).thenReturn(emptyServletInputStream());
        return req;
    }

    private CommitConfig testConfig() {
        return CommitConfig.builder()
                .author(CommitConfig.AuthorConfig.builder()
                        .email(CommitConfig.EmailConfig.builder()
                                .domain(CommitConfig.DomainConfig.builder()
                                        .allow(Pattern.compile("(example\\.com|company\\.org)$"))
                                        .build())
                                .local(CommitConfig.LocalConfig.builder()
                                        .block(Pattern.compile("^(noreply|no-reply|bot)$"))
                                        .build())
                                .build())
                        .build())
                .build();
    }

    private GitRequestDetails makeRequestDetails(List<Commit> commits) {
        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.PUSH);
        details.getPushedCommits().addAll(commits);
        return details;
    }

    private Commit commitWithEmail(String email) {
        return Commit.builder()
                .sha("abc123def456")
                .author(Contributor.builder().name("Test User").email(email).build())
                .committer(Contributor.builder().name("Test User").email(email).build())
                .message("Test commit message")
                .build();
    }

    // ---- tests ----

    @Test
    void allowedEmail_passes() throws Exception {
        GitRequestDetails details = makeRequestDetails(List.of(commitWithEmail("user@example.com")));
        CheckAuthorEmailsFilter filter = new CheckAuthorEmailsFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.PENDING, details.getResult());
        assertFalse(fakeResponse.committed.get(), "Response must not be committed for allowed email");
    }

    @Test
    void allowedCompanyEmail_passes() throws Exception {
        GitRequestDetails details = makeRequestDetails(List.of(commitWithEmail("dev@company.org")));
        CheckAuthorEmailsFilter filter = new CheckAuthorEmailsFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.PENDING, details.getResult());
        assertFalse(fakeResponse.committed.get());
    }

    @Test
    void disallowedDomain_blocks() throws Exception {
        GitRequestDetails details = makeRequestDetails(List.of(commitWithEmail("user@unknown.io")));
        CheckAuthorEmailsFilter filter = new CheckAuthorEmailsFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        assertFalse(
                fakeResponse.committed.get(),
                "Response must NOT be committed — recordIssue defers to ValidationSummaryFilter");
    }

    @Test
    void disallowedDomain_recordsBlockedStep() throws Exception {
        GitRequestDetails details = makeRequestDetails(List.of(commitWithEmail("user@unknown.io")));
        CheckAuthorEmailsFilter filter = new CheckAuthorEmailsFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertFalse(details.getSteps().isEmpty(), "At least one step must be recorded");
        assertTrue(
                details.getSteps().stream().anyMatch(s -> s.getStatus() == StepStatus.FAIL),
                "A BLOCKED step must be recorded");
    }

    @Test
    void blockedLocalPart_noreply_blocks() throws Exception {
        GitRequestDetails details = makeRequestDetails(List.of(commitWithEmail("noreply@example.com")));
        CheckAuthorEmailsFilter filter = new CheckAuthorEmailsFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
    }

    @Test
    void blockedLocalPart_bot_blocks() throws Exception {
        GitRequestDetails details = makeRequestDetails(List.of(commitWithEmail("bot@example.com")));
        CheckAuthorEmailsFilter filter = new CheckAuthorEmailsFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
    }

    @Test
    void invalidEmailFormat_blocks() throws Exception {
        GitRequestDetails details = makeRequestDetails(List.of(commitWithEmail("notanemail")));
        CheckAuthorEmailsFilter filter = new CheckAuthorEmailsFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
    }

    @Test
    void emptyEmail_blocks() throws Exception {
        GitRequestDetails details = makeRequestDetails(List.of(commitWithEmail("")));
        CheckAuthorEmailsFilter filter = new CheckAuthorEmailsFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
    }

    @Test
    void nullPushedCommits_doesNotBlock() throws Exception {
        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.PUSH);
        details.setPushedCommits(null);
        CheckAuthorEmailsFilter filter = new CheckAuthorEmailsFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.PENDING, details.getResult());
    }

    @Test
    void emptyPushedCommits_doesNotBlock() throws Exception {
        GitRequestDetails details = makeRequestDetails(List.of());
        CheckAuthorEmailsFilter filter = new CheckAuthorEmailsFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.PENDING, details.getResult());
    }

    @Test
    void nullRequestDetails_doesNotThrow() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("POST");
        when(req.getContentType()).thenReturn("application/x-git-receive-pack-request");
        when(req.getRequestURI()).thenReturn("/proxy/github.com/owner/repo.git/git-receive-pack");
        when(req.getAttribute(GIT_REQUEST_ATTR)).thenReturn(null);
        when(req.getInputStream()).thenReturn(emptyServletInputStream());

        CheckAuthorEmailsFilter filter = new CheckAuthorEmailsFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        // Must not throw
        assertDoesNotThrow(() -> filter.doHttpFilter(req, fakeResponse.mock));
    }

    @Test
    void multipleCommits_allValid_passes() throws Exception {
        GitRequestDetails details =
                makeRequestDetails(List.of(commitWithEmail("alice@example.com"), commitWithEmail("bob@company.org")));
        CheckAuthorEmailsFilter filter = new CheckAuthorEmailsFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.PENDING, details.getResult());
    }

    @Test
    void multipleCommits_oneInvalid_blocks() throws Exception {
        GitRequestDetails details =
                makeRequestDetails(List.of(commitWithEmail("alice@example.com"), commitWithEmail("outsider@evil.io")));
        CheckAuthorEmailsFilter filter = new CheckAuthorEmailsFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
    }

    @Test
    void noConfigRestrictions_anyEmailPasses() throws Exception {
        GitRequestDetails details = makeRequestDetails(List.of(commitWithEmail("anything@notrestricted.biz")));
        CheckAuthorEmailsFilter filter = new CheckAuthorEmailsFilter(CommitConfig.defaultConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.PENDING, details.getResult());
    }
}
