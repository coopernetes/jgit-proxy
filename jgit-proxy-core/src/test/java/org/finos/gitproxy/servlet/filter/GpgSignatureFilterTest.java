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
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.finos.gitproxy.config.GpgConfig;
import org.finos.gitproxy.git.Commit;
import org.finos.gitproxy.git.Contributor;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.junit.jupiter.api.Test;

class GpgSignatureFilterTest {

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

    private Commit unsignedCommit() {
        return Commit.builder()
                .sha("abc123def456")
                .author(Contributor.builder()
                        .name("Dev")
                        .email("dev@example.com")
                        .build())
                .committer(Contributor.builder()
                        .name("Dev")
                        .email("dev@example.com")
                        .build())
                .message("feat: something")
                .date(Instant.now())
                .build();
    }

    @Test
    void disabled_passes_withoutCheckingCommits() throws Exception {
        GpgConfig config = GpgConfig.builder().enabled(false).build();
        GpgSignatureFilter filter = new GpgSignatureFilter(config);
        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.PUSH);
        details.getPushedCommits().add(unsignedCommit());
        FakeResponse resp = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), resp.mock);

        assertFalse(resp.committed.get(), "Disabled GPG filter must not block");
    }

    @Test
    void nullRequestDetails_returnsWithoutError() throws Exception {
        GpgConfig config =
                GpgConfig.builder().enabled(true).requireSignedCommits(true).build();
        GpgSignatureFilter filter = new GpgSignatureFilter(config);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("POST");
        when(req.getContentType()).thenReturn("application/x-git-receive-pack-request");
        when(req.getRequestURI()).thenReturn("/proxy/github.com/owner/repo.git/git-receive-pack");
        when(req.getAttribute(GIT_REQUEST_ATTRIBUTE)).thenReturn(null);
        when(req.getInputStream()).thenReturn(emptyServletInputStream());
        FakeResponse resp = new FakeResponse();

        filter.doHttpFilter(req, resp.mock);

        assertFalse(resp.committed.get());
    }

    @Test
    void enabled_noCommits_passes() throws Exception {
        GpgConfig config =
                GpgConfig.builder().enabled(true).requireSignedCommits(true).build();
        GpgSignatureFilter filter = new GpgSignatureFilter(config);
        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.PUSH);
        // no commits added
        FakeResponse resp = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), resp.mock);

        assertFalse(resp.committed.get(), "No commits should pass without error");
    }

    @Test
    void requireSignedCommits_unsignedCommit_blocks() throws Exception {
        GpgConfig config =
                GpgConfig.builder().enabled(true).requireSignedCommits(true).build();
        GpgSignatureFilter filter = new GpgSignatureFilter(config);
        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.PUSH);
        details.getPushedCommits().add(unsignedCommit());
        FakeResponse resp = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), resp.mock);

        assertTrue(resp.committed.get(), "Unsigned commit when required should be blocked");
        assertEquals(GitRequestDetails.GitResult.BLOCKED, details.getResult());
    }

    @Test
    void requireSignedCommits_false_unsignedCommit_passes() throws Exception {
        GpgConfig config =
                GpgConfig.builder().enabled(true).requireSignedCommits(false).build();
        GpgSignatureFilter filter = new GpgSignatureFilter(config);
        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.PUSH);
        details.getPushedCommits().add(unsignedCommit());
        FakeResponse resp = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), resp.mock);

        assertFalse(resp.committed.get(), "Unsigned commit when not required should pass");
    }

    @Test
    void nullConfig_defaultsToDisabled() throws Exception {
        GpgSignatureFilter filter = new GpgSignatureFilter(null);
        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.PUSH);
        details.getPushedCommits().add(unsignedCommit());
        FakeResponse resp = new FakeResponse();

        // null config defaults to GpgConfig.defaultConfig() which has enabled=false
        filter.doHttpFilter(mockPushRequest(details), resp.mock);

        assertFalse(resp.committed.get(), "Null config should default to disabled");
    }

    @Test
    void multipleUnsignedCommits_blocksOnFirst() throws Exception {
        GpgConfig config =
                GpgConfig.builder().enabled(true).requireSignedCommits(true).build();
        GpgSignatureFilter filter = new GpgSignatureFilter(config);
        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.PUSH);
        details.getPushedCommits().addAll(List.of(unsignedCommit(), unsignedCommit()));
        FakeResponse resp = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), resp.mock);

        assertTrue(resp.committed.get(), "Should block on first unsigned commit");
    }
}
