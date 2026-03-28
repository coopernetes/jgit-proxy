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

class CheckCommitMessagesFilterTest {

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
        when(req.getAttribute(GIT_REQUEST_ATTRIBUTE)).thenReturn(details);
        when(req.getInputStream()).thenReturn(emptyServletInputStream());
        return req;
    }

    private CommitConfig testConfig() {
        return CommitConfig.builder()
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

    private Commit commitWithMessage(String message) {
        return Commit.builder()
                .sha("abc123def456")
                .author(Contributor.builder()
                        .name("Test User")
                        .email("user@example.com")
                        .build())
                .committer(Contributor.builder()
                        .name("Test User")
                        .email("user@example.com")
                        .build())
                .message(message)
                .build();
    }

    // ---- tests ----

    @Test
    void normalMessage_passes() throws Exception {
        GitRequestDetails details = makeRequestDetails(List.of(commitWithMessage("Fix bug in auth module")));
        CheckCommitMessagesFilter filter = new CheckCommitMessagesFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.ALLOWED, details.getResult());
        assertFalse(fakeResponse.committed.get());
    }

    @Test
    void wipMessage_blocks() throws Exception {
        GitRequestDetails details = makeRequestDetails(List.of(commitWithMessage("WIP: adding feature")));
        CheckCommitMessagesFilter filter = new CheckCommitMessagesFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.BLOCKED, details.getResult());
        assertTrue(fakeResponse.committed.get());
    }

    @Test
    void doNotMergeMessage_blocks() throws Exception {
        GitRequestDetails details = makeRequestDetails(List.of(commitWithMessage("DO NOT MERGE - experiment")));
        CheckCommitMessagesFilter filter = new CheckCommitMessagesFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.BLOCKED, details.getResult());
    }

    @Test
    void fixupMessage_blocks() throws Exception {
        GitRequestDetails details = makeRequestDetails(List.of(commitWithMessage("fixup! previous commit")));
        CheckCommitMessagesFilter filter = new CheckCommitMessagesFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.BLOCKED, details.getResult());
    }

    @Test
    void squashMessage_blocks() throws Exception {
        GitRequestDetails details = makeRequestDetails(List.of(commitWithMessage("squash! cleanup")));
        CheckCommitMessagesFilter filter = new CheckCommitMessagesFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.BLOCKED, details.getResult());
    }

    @Test
    void wipCaseInsensitive_blocks() throws Exception {
        // The literal check is case-insensitive per isMessageAllowed()
        GitRequestDetails details = makeRequestDetails(List.of(commitWithMessage("wip: adding feature")));
        CheckCommitMessagesFilter filter = new CheckCommitMessagesFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.BLOCKED, details.getResult());
    }

    @Test
    void passwordInMessage_blocks() throws Exception {
        GitRequestDetails details =
                makeRequestDetails(List.of(commitWithMessage("Add config with password=secret123")));
        CheckCommitMessagesFilter filter = new CheckCommitMessagesFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.BLOCKED, details.getResult());
    }

    @Test
    void secretPattern_blocks() throws Exception {
        GitRequestDetails details = makeRequestDetails(List.of(commitWithMessage("Set token: abc123xyz")));
        CheckCommitMessagesFilter filter = new CheckCommitMessagesFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.BLOCKED, details.getResult());
    }

    @Test
    void emptyMessage_blocks() throws Exception {
        GitRequestDetails details = makeRequestDetails(List.of(commitWithMessage("")));
        CheckCommitMessagesFilter filter = new CheckCommitMessagesFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.BLOCKED, details.getResult());
    }

    @Test
    void nullMessage_throwsNPE() throws Exception {
        // A commit with a null message causes a NullPointerException in the filter's
        // message-formatting code (String.lines() on null). This documents the known
        // edge-case behavior: null messages are detected as invalid but NPE occurs
        // before the BLOCKED result is recorded.
        Commit nullMsgCommit = Commit.builder()
                .sha("abc123def456")
                .author(Contributor.builder()
                        .name("Test User")
                        .email("user@example.com")
                        .build())
                .committer(Contributor.builder()
                        .name("Test User")
                        .email("user@example.com")
                        .build())
                .message(null)
                .build();
        GitRequestDetails details = makeRequestDetails(List.of(nullMsgCommit));
        CheckCommitMessagesFilter filter = new CheckCommitMessagesFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        assertThrows(
                NullPointerException.class,
                () -> filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock),
                "Null commit message should cause NPE in the filter's formatting code");
    }

    @Test
    void nullPushedCommits_doesNotBlock() throws Exception {
        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.PUSH);
        details.setPushedCommits(null);
        CheckCommitMessagesFilter filter = new CheckCommitMessagesFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.ALLOWED, details.getResult());
    }

    @Test
    void emptyPushedCommits_doesNotBlock() throws Exception {
        GitRequestDetails details = makeRequestDetails(List.of());
        CheckCommitMessagesFilter filter = new CheckCommitMessagesFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.ALLOWED, details.getResult());
    }

    @Test
    void blockedMessage_recordsBlockedStep() throws Exception {
        GitRequestDetails details = makeRequestDetails(List.of(commitWithMessage("WIP: adding feature")));
        CheckCommitMessagesFilter filter = new CheckCommitMessagesFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertFalse(details.getSteps().isEmpty());
        assertTrue(details.getSteps().stream().anyMatch(s -> s.getStatus() == StepStatus.BLOCKED));
    }

    @Test
    void multipleCommits_oneBlocked_blocks() throws Exception {
        GitRequestDetails details =
                makeRequestDetails(List.of(commitWithMessage("Clean commit"), commitWithMessage("WIP: in progress")));
        CheckCommitMessagesFilter filter = new CheckCommitMessagesFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.BLOCKED, details.getResult());
    }

    @Test
    void allCleanMessages_passes() throws Exception {
        GitRequestDetails details = makeRequestDetails(List.of(
                commitWithMessage("Fix login bug"),
                commitWithMessage("Refactor auth module"),
                commitWithMessage("Add unit tests")));
        CheckCommitMessagesFilter filter = new CheckCommitMessagesFilter(testConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.ALLOWED, details.getResult());
    }

    @Test
    void noConfigRestrictions_anyMessagePasses() throws Exception {
        // Even "WIP" should pass when there is no config
        GitRequestDetails details = makeRequestDetails(List.of(commitWithMessage("WIP")));
        CheckCommitMessagesFilter filter = new CheckCommitMessagesFilter(CommitConfig.defaultConfig());
        FakeResponse fakeResponse = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.ALLOWED, details.getResult());
    }
}
