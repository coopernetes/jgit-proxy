package org.finos.gitproxy.servlet.filter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Enumeration;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.provider.GitHubProvider;
import org.finos.gitproxy.servlet.RequestBodyWrapper;
import org.junit.jupiter.api.Test;

class ParseGitRequestFilterTest {

    // Push 1 constants
    private static final String PUSH1_OLD = "61a0b5dd65652ed278b2f569c1ce5dea0e02ce61";
    private static final String PUSH1_NEW = "3348d03785fdeb43cc0b72077e9d2d7512c01a72";
    private static final String PUSH1_REF = "refs/heads/main";

    // Push 2 constants
    private static final String PUSH2_OLD = "3348d03785fdeb43cc0b72077e9d2d7512c01a72";
    private static final String PUSH2_NEW = "5b8690554d2ddd65f28466b829fc9b6879e2ba2d";

    // ---- helpers ----

    private static class MockServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream is;

        MockServletInputStream(byte[] data) {
            this.is = new ByteArrayInputStream(data);
        }

        @Override
        public int read() throws IOException {
            return is.read();
        }

        @Override
        public boolean isFinished() {
            return is.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener l) {}
    }

    private byte[] loadResource(String name) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(is, "Resource not found: " + name);
            return is.readAllBytes();
        }
    }

    /**
     * Wrap a raw body byte array into a RequestBodyWrapper backed by a mock HttpServletRequest. The body is the full
     * git receive-pack body (includes the 4-char hex length prefix).
     */
    private RequestBodyWrapper wrapBody(byte[] body, String pathInfo) throws IOException {
        HttpServletRequest inner = mock(HttpServletRequest.class);
        when(inner.getMethod()).thenReturn("POST");
        when(inner.getContentType()).thenReturn("application/x-git-receive-pack-request");
        when(inner.getRequestURI()).thenReturn("/proxy/github.com/owner/repo.git/git-receive-pack");
        when(inner.getPathInfo()).thenReturn(pathInfo);
        when(inner.getInputStream()).thenReturn(new MockServletInputStream(body));
        Enumeration<String> emptyEnum = Collections.emptyEnumeration();
        when(inner.getHeaderNames()).thenReturn(emptyEnum);
        return new RequestBodyWrapper(inner);
    }

    private ParseGitRequestFilter makeFilter() {
        return new ParseGitRequestFilter(new GitHubProvider("/proxy"));
    }

    // ---- tests ----

    @Test
    void parse_pushRequest_detectsOperation() throws Exception {
        byte[] body = loadResource("push-sample-01-body.bin");
        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");

        GitRequestDetails details = makeFilter().parse(wrapper);

        assertEquals(HttpOperation.PUSH, details.getOperation());
    }

    @Test
    void parse_pushRequest_extractsCorrectFromSha() throws Exception {
        byte[] body = loadResource("push-sample-01-body.bin");
        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");

        GitRequestDetails details = makeFilter().parse(wrapper);

        assertEquals(PUSH1_OLD, details.getCommitFrom());
    }

    @Test
    void parse_pushRequest_extractsCorrectToSha() throws Exception {
        byte[] body = loadResource("push-sample-01-body.bin");
        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");

        GitRequestDetails details = makeFilter().parse(wrapper);

        assertEquals(PUSH1_NEW, details.getCommitTo());
    }

    @Test
    void parse_pushRequest_extractsCorrectReference() throws Exception {
        byte[] body = loadResource("push-sample-01-body.bin");
        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");

        GitRequestDetails details = makeFilter().parse(wrapper);

        assertEquals(PUSH1_REF, details.getBranch());
    }

    @Test
    void parse_pushRequest_extractsRepositoryOwner() throws Exception {
        byte[] body = loadResource("push-sample-01-body.bin");
        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");

        GitRequestDetails details = makeFilter().parse(wrapper);

        assertNotNull(details.getRepoRef());
        assertEquals("owner", details.getRepoRef().getOwner());
    }

    @Test
    void parse_pushRequest_extractsRepositoryName() throws Exception {
        byte[] body = loadResource("push-sample-01-body.bin");
        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");

        GitRequestDetails details = makeFilter().parse(wrapper);

        assertNotNull(details.getRepoRef());
        assertEquals("repo", details.getRepoRef().getName());
    }

    @Test
    void parse_pushRequest_hasNonNullCommit() throws Exception {
        byte[] body = loadResource("push-sample-01-body.bin");
        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");

        GitRequestDetails details = makeFilter().parse(wrapper);

        assertNotNull(details.getCommit(), "Parsed commit must not be null");
    }

    @Test
    void parse_secondSample_extractsOldSha() throws Exception {
        byte[] body = loadResource("push-sample-02-body.bin");
        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");

        GitRequestDetails details = makeFilter().parse(wrapper);

        assertEquals(PUSH2_OLD, details.getCommitFrom());
    }

    @Test
    void parse_secondSample_extractsNewSha() throws Exception {
        byte[] body = loadResource("push-sample-02-body.bin");
        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");

        GitRequestDetails details = makeFilter().parse(wrapper);

        assertEquals(PUSH2_NEW, details.getCommitTo());
    }
}
