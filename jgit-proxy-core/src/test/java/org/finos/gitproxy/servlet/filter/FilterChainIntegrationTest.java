package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;
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

/**
 * Integration tests for the filter pipeline combining CheckAuthorEmailsFilter and CheckCommitMessagesFilter chained
 * together.
 */
class FilterChainIntegrationTest {

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

    private CommitConfig emailAndMessageConfig() {
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
                .message(CommitConfig.MessageConfig.builder()
                        .block(CommitConfig.BlockConfig.builder()
                                .literals(List.of("WIP", "DO NOT MERGE", "fixup!", "squash!"))
                                .patterns(List.of(Pattern.compile("(?i)(password|secret|token)\\s*[=:]\\s*\\S+")))
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

    private Commit validCommit() {
        return Commit.builder()
                .sha("abc123")
                .author(Contributor.builder()
                        .name("Dev")
                        .email("dev@example.com")
                        .build())
                .committer(Contributor.builder()
                        .name("Dev")
                        .email("dev@example.com")
                        .build())
                .message("Add feature X")
                .build();
    }

    private Commit commitWithEmail(String email) {
        return Commit.builder()
                .sha("abc123")
                .author(Contributor.builder().name("Dev").email(email).build())
                .committer(Contributor.builder().name("Dev").email(email).build())
                .message("Add feature X")
                .build();
    }

    private Commit commitWithMessage(String message) {
        return Commit.builder()
                .sha("abc123")
                .author(Contributor.builder()
                        .name("Dev")
                        .email("dev@example.com")
                        .build())
                .committer(Contributor.builder()
                        .name("Dev")
                        .email("dev@example.com")
                        .build())
                .message(message)
                .build();
    }

    private void runFilterChain(
            CheckAuthorEmailsFilter emailFilter,
            CheckCommitMessagesFilter messageFilter,
            HttpServletRequest request,
            HttpServletResponse response)
            throws Exception {
        FilterChain finalChain = (req, resp) -> {};
        FilterChain afterEmail = (req, resp) -> messageFilter.doFilter(req, resp, finalChain);
        emailFilter.doFilter(request, response, afterEmail);
    }

    // ---- tests ----

    @Test
    void allValid_fullChainPasses() throws Exception {
        CommitConfig config = emailAndMessageConfig();
        GitRequestDetails details = makeRequestDetails(List.of(validCommit()));
        CheckAuthorEmailsFilter emailFilter = new CheckAuthorEmailsFilter(config);
        CheckCommitMessagesFilter messageFilter = new CheckCommitMessagesFilter(config);
        FakeResponse fakeResponse = new FakeResponse();
        HttpServletRequest req = mockPushRequest(details);

        runFilterChain(emailFilter, messageFilter, req, fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.PENDING, details.getResult());
        // Both filters ran and recorded PASS steps
        assertTrue(
                details.getSteps().stream()
                        .anyMatch(s ->
                                s.getStepName().equals("CheckAuthorEmailsFilter") && s.getStatus() == StepStatus.PASS),
                "Email filter should have a PASS step");
        assertTrue(
                details.getSteps().stream()
                        .anyMatch(s -> s.getStepName().equals("CheckCommitMessagesFilter")
                                && s.getStatus() == StepStatus.PASS),
                "Message filter should have a PASS step");
    }

    @Test
    void invalidEmail_blocksBeforeMessageFilter() throws Exception {
        CommitConfig config = emailAndMessageConfig();
        // bad domain → email filter should block
        GitRequestDetails details = makeRequestDetails(List.of(commitWithEmail("user@invalid.io")));
        CheckAuthorEmailsFilter emailFilter = new CheckAuthorEmailsFilter(config);
        CheckCommitMessagesFilter messageFilter = new CheckCommitMessagesFilter(config);
        FakeResponse fakeResponse = new FakeResponse();
        HttpServletRequest req = mockPushRequest(details);

        runFilterChain(emailFilter, messageFilter, req, fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        // Email filter BLOCKED step is present
        assertTrue(details.getSteps().stream()
                .anyMatch(s -> s.getStepName().equals("CheckAuthorEmailsFilter") && s.getStatus() == StepStatus.FAIL));
        // Message filter MUST have run (aggregate validation — all filters continue after recordIssue)
        assertTrue(
                details.getSteps().stream().anyMatch(s -> s.getStepName().equals("CheckCommitMessagesFilter")),
                "Message filter must still run even after email filter recorded an issue");
    }

    @Test
    void validEmailButWipMessage_emailPassesMessageBlocks() throws Exception {
        CommitConfig config = emailAndMessageConfig();
        GitRequestDetails details = makeRequestDetails(List.of(commitWithMessage("WIP: not done yet")));
        CheckAuthorEmailsFilter emailFilter = new CheckAuthorEmailsFilter(config);
        CheckCommitMessagesFilter messageFilter = new CheckCommitMessagesFilter(config);
        FakeResponse fakeResponse = new FakeResponse();
        HttpServletRequest req = mockPushRequest(details);

        runFilterChain(emailFilter, messageFilter, req, fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        // Email filter has a PASS step
        assertTrue(details.getSteps().stream()
                .anyMatch(s -> s.getStepName().equals("CheckAuthorEmailsFilter") && s.getStatus() == StepStatus.PASS));
        // Message filter has a BLOCKED step
        assertTrue(details.getSteps().stream()
                .anyMatch(
                        s -> s.getStepName().equals("CheckCommitMessagesFilter") && s.getStatus() == StepStatus.FAIL));
    }

    @Test
    void invalidEmailAndWipMessage_bothFiltersBlock() throws Exception {
        CommitConfig config = emailAndMessageConfig();
        // Commit has both bad email AND bad message - email filter fires first
        Commit both = Commit.builder()
                .sha("abc123")
                .author(Contributor.builder().name("Dev").email("user@evil.io").build())
                .committer(
                        Contributor.builder().name("Dev").email("user@evil.io").build())
                .message("WIP: broken")
                .build();
        GitRequestDetails details = makeRequestDetails(List.of(both));
        CheckAuthorEmailsFilter emailFilter = new CheckAuthorEmailsFilter(config);
        CheckCommitMessagesFilter messageFilter = new CheckCommitMessagesFilter(config);
        FakeResponse fakeResponse = new FakeResponse();
        HttpServletRequest req = mockPushRequest(details);

        runFilterChain(emailFilter, messageFilter, req, fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        // Email filter blocked
        assertTrue(details.getSteps().stream()
                .anyMatch(s -> s.getStepName().equals("CheckAuthorEmailsFilter") && s.getStatus() == StepStatus.FAIL));
        // Message filter also ran and also blocked (aggregate: all failures reported)
        assertTrue(
                details.getSteps().stream()
                        .anyMatch(s -> s.getStepName().equals("CheckCommitMessagesFilter")
                                && s.getStatus() == StepStatus.FAIL),
                "Message filter must also run and record its own BLOCKED step");
    }

    @Test
    void multipleCommits_validEmails_validMessages_passes() throws Exception {
        CommitConfig config = emailAndMessageConfig();
        GitRequestDetails details = makeRequestDetails(List.of(
                validCommit(),
                Commit.builder()
                        .sha("def456")
                        .author(Contributor.builder()
                                .name("Alice")
                                .email("alice@example.com")
                                .build())
                        .committer(Contributor.builder()
                                .name("Alice")
                                .email("alice@example.com")
                                .build())
                        .message("Refactor auth")
                        .build(),
                Commit.builder()
                        .sha("789abc")
                        .author(Contributor.builder()
                                .name("Bob")
                                .email("bob@company.org")
                                .build())
                        .committer(Contributor.builder()
                                .name("Bob")
                                .email("bob@company.org")
                                .build())
                        .message("Add tests")
                        .build()));
        CheckAuthorEmailsFilter emailFilter = new CheckAuthorEmailsFilter(config);
        CheckCommitMessagesFilter messageFilter = new CheckCommitMessagesFilter(config);
        FakeResponse fakeResponse = new FakeResponse();
        HttpServletRequest req = mockPushRequest(details);

        runFilterChain(emailFilter, messageFilter, req, fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.PENDING, details.getResult());
    }

    @Test
    void multipleCommits_oneInvalidEmail_blocks() throws Exception {
        CommitConfig config = emailAndMessageConfig();
        GitRequestDetails details =
                makeRequestDetails(List.of(validCommit(), commitWithEmail("outsider@corp.internal"), validCommit()));
        CheckAuthorEmailsFilter emailFilter = new CheckAuthorEmailsFilter(config);
        CheckCommitMessagesFilter messageFilter = new CheckCommitMessagesFilter(config);
        FakeResponse fakeResponse = new FakeResponse();
        HttpServletRequest req = mockPushRequest(details);

        runFilterChain(emailFilter, messageFilter, req, fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
    }
}
