package org.finos.gitproxy.jetty;

import static org.finos.gitproxy.servlet.GitProxyProviderServlet.GIT_REQUEST_ATTRIBUTE;
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
import org.finos.gitproxy.git.Commit;
import org.finos.gitproxy.git.Contributor;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.servlet.filter.CheckAuthorEmailsFilter;
import org.finos.gitproxy.servlet.filter.CheckCommitMessagesFilter;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the proxy-mode filter pipeline using a production-like CommitConfig that mirrors what is
 * assembled in GitProxyJettyApplication.
 */
class ProxyModeFilterChainTest {

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

    /** Production-like config matching what GitProxyJettyApplication would build. */
    private CommitConfig productionLikeConfig() {
        return CommitConfig.builder()
                .author(CommitConfig.AuthorConfig.builder()
                        .email(CommitConfig.EmailConfig.builder()
                                .domain(CommitConfig.DomainConfig.builder()
                                        .allow(Pattern.compile(
                                                "(proton\\.me|gmail\\.com|outlook\\.com|yahoo\\.com|example\\.com)$"))
                                        .build())
                                .local(CommitConfig.LocalConfig.builder()
                                        .block(Pattern.compile("^(noreply|no-reply|bot|nobody)$"))
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

    private Commit commitWithEmail(String email) {
        return Commit.builder()
                .sha("abc123def456")
                .author(Contributor.builder().name("User").email(email).build())
                .committer(Contributor.builder().name("User").email(email).build())
                .message("Implement feature")
                .build();
    }

    private Commit commitWithEmailAndMessage(String email, String message) {
        return Commit.builder()
                .sha("abc123def456")
                .author(Contributor.builder().name("User").email(email).build())
                .committer(Contributor.builder().name("User").email(email).build())
                .message(message)
                .build();
    }

    private void runChain(CommitConfig config, HttpServletRequest req, HttpServletResponse resp) throws Exception {
        CheckAuthorEmailsFilter emailFilter = new CheckAuthorEmailsFilter(config);
        CheckCommitMessagesFilter messageFilter = new CheckCommitMessagesFilter(config);
        FilterChain finalChain = (r, s) -> {};
        FilterChain afterEmail = (r, s) -> messageFilter.doFilter(r, s, finalChain);
        emailFilter.doFilter(req, resp, afterEmail);
    }

    // ---- tests ----

    @Test
    void productionConfig_validGmailEmail_passes() throws Exception {
        CommitConfig config = productionLikeConfig();
        GitRequestDetails details = makeRequestDetails(List.of(commitWithEmail("user@gmail.com")));
        FakeResponse fakeResponse = new FakeResponse();

        runChain(config, mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.ALLOWED, details.getResult());
        assertFalse(fakeResponse.committed.get());
    }

    @Test
    void productionConfig_noReplyEmail_blocks() throws Exception {
        CommitConfig config = productionLikeConfig();
        GitRequestDetails details = makeRequestDetails(List.of(commitWithEmail("noreply@gmail.com")));
        FakeResponse fakeResponse = new FakeResponse();

        runChain(config, mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.BLOCKED, details.getResult());
    }

    @Test
    void productionConfig_unknownDomain_blocks() throws Exception {
        CommitConfig config = productionLikeConfig();
        GitRequestDetails details = makeRequestDetails(List.of(commitWithEmail("user@corporate.internal")));
        FakeResponse fakeResponse = new FakeResponse();

        runChain(config, mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.BLOCKED, details.getResult());
    }

    @Test
    void productionConfig_wipMessage_blocks() throws Exception {
        CommitConfig config = productionLikeConfig();
        GitRequestDetails details =
                makeRequestDetails(List.of(commitWithEmailAndMessage("user@gmail.com", "WIP: in progress")));
        FakeResponse fakeResponse = new FakeResponse();

        runChain(config, mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.BLOCKED, details.getResult());
    }

    @Test
    void productionConfig_passwordInMessage_blocks() throws Exception {
        CommitConfig config = productionLikeConfig();
        GitRequestDetails details =
                makeRequestDetails(List.of(commitWithEmailAndMessage("user@gmail.com", "Set token: abc123")));
        FakeResponse fakeResponse = new FakeResponse();

        runChain(config, mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.BLOCKED, details.getResult());
    }

    @Test
    void productionConfig_botLocalPart_blocks() throws Exception {
        CommitConfig config = productionLikeConfig();
        GitRequestDetails details = makeRequestDetails(List.of(commitWithEmail("bot@example.com")));
        FakeResponse fakeResponse = new FakeResponse();

        runChain(config, mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.BLOCKED, details.getResult());
    }

    @Test
    void productionConfig_protonmailEmail_passes() throws Exception {
        CommitConfig config = productionLikeConfig();
        GitRequestDetails details =
                makeRequestDetails(List.of(commitWithEmailAndMessage("user@proton.me", "Add new feature")));
        FakeResponse fakeResponse = new FakeResponse();

        runChain(config, mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.ALLOWED, details.getResult());
        assertFalse(fakeResponse.committed.get());
    }

    @Test
    void productionConfig_outlookEmail_cleanMessage_passes() throws Exception {
        CommitConfig config = productionLikeConfig();
        GitRequestDetails details =
                makeRequestDetails(List.of(commitWithEmailAndMessage("dev@outlook.com", "Refactor login module")));
        FakeResponse fakeResponse = new FakeResponse();

        runChain(config, mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.ALLOWED, details.getResult());
    }

    @Test
    void productionConfig_nobodyLocalPart_blocks() throws Exception {
        CommitConfig config = productionLikeConfig();
        GitRequestDetails details = makeRequestDetails(List.of(commitWithEmail("nobody@gmail.com")));
        FakeResponse fakeResponse = new FakeResponse();

        runChain(config, mockPushRequest(details), fakeResponse.mock);

        assertEquals(GitRequestDetails.GitResult.BLOCKED, details.getResult());
    }
}
