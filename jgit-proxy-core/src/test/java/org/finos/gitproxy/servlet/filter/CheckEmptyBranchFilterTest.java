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
import java.util.concurrent.atomic.AtomicBoolean;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.junit.jupiter.api.Test;

class CheckEmptyBranchFilterTest {

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

    private static ServletInputStream emptyInputStream() {
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

    private static HttpServletRequest mockPushRequest(GitRequestDetails details) throws IOException {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getContentType()).thenReturn("application/x-git-receive-pack-request");
        when(req.getRequestURI()).thenReturn("/proxy/github.com/owner/repo.git/git-receive-pack");
        when(req.getAttribute(GIT_REQUEST_ATTR)).thenReturn(details);
        when(req.getInputStream()).thenReturn(emptyInputStream());
        return req;
    }

    private static GitRequestDetails detailsWithCommits() {
        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.PUSH);
        details.setCommitFrom("0000000000000000000000000000000000000000");
        details.setCommitTo("abc123def456abc123def456abc123def456abc12");
        details.getPushedCommits()
                .add(org.finos.gitproxy.git.Commit.builder()
                        .sha("abc123")
                        .author(org.finos.gitproxy.git.Contributor.builder()
                                .name("Dev")
                                .email("dev@example.com")
                                .build())
                        .committer(org.finos.gitproxy.git.Contributor.builder()
                                .name("Dev")
                                .email("dev@example.com")
                                .build())
                        .message("Add feature")
                        .build());
        return details;
    }

    private static GitRequestDetails detailsWithNoCommits(boolean isNewBranch) {
        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.PUSH);
        details.setCommitFrom(
                isNewBranch ? "0000000000000000000000000000000000000000" : "abc000def456abc123def456abc123def456abc12");
        details.setCommitTo("abc123def456abc123def456abc123def456abc12");
        // pushedCommits left empty
        return details;
    }

    // ---- tests ----

    @Test
    void commitsPresent_filterPassesThrough() throws Exception {
        GitRequestDetails details = detailsWithCommits();
        FakeResponse fakeResponse = new FakeResponse();
        CheckEmptyBranchFilter filter = new CheckEmptyBranchFilter();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertFalse(fakeResponse.committed.get(), "Response must not be written when commits are present");
        assertEquals(GitRequestDetails.GitResult.PENDING, details.getResult());
    }

    @Test
    void newBranchNoCommits_blocked() throws Exception {
        GitRequestDetails details = detailsWithNoCommits(true);
        FakeResponse fakeResponse = new FakeResponse();
        CheckEmptyBranchFilter filter = new CheckEmptyBranchFilter();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertTrue(fakeResponse.committed.get(), "Response must be written for empty new branch");
        // Body should mention the empty-branch message
        String body = fakeResponse.body.toString();
        assertTrue(body.contains("Empty Branch") || body.contains("commit"), "Body must explain the rejection");
    }

    @Test
    void existingBranchNoCommitData_blocked() throws Exception {
        GitRequestDetails details = detailsWithNoCommits(false);
        FakeResponse fakeResponse = new FakeResponse();
        CheckEmptyBranchFilter filter = new CheckEmptyBranchFilter();

        filter.doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertTrue(fakeResponse.committed.get(), "Response must be written when commit data is missing");
        String body = fakeResponse.body.toString();
        assertTrue(body.contains("Not Found") || body.contains("administrator") || body.length() > 0);
    }

    @Test
    void tagPush_skipped() throws Exception {
        // Tags always point to an existing commit — pushedCommits is empty and commitFrom is zeros,
        // which normally triggers the "empty new branch" rejection. Tags must be skipped.
        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.PUSH);
        details.setBranch("refs/tags/v1.0");
        details.setCommitFrom("0000000000000000000000000000000000000000");
        details.setCommitTo("abc123def456abc123def456abc123def456abc12");
        // pushedCommits left empty
        FakeResponse fakeResponse = new FakeResponse();

        new CheckEmptyBranchFilter().doHttpFilter(mockPushRequest(details), fakeResponse.mock);

        assertFalse(fakeResponse.committed.get(), "Tag push must not be rejected as an empty branch");
        assertEquals(GitRequestDetails.GitResult.PENDING, details.getResult());
    }

    @Test
    void nullRequestDetails_filterDoesNothing() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getContentType()).thenReturn("application/x-git-receive-pack-request");
        when(req.getRequestURI()).thenReturn("/proxy/github.com/owner/repo.git/git-receive-pack");
        when(req.getAttribute(GIT_REQUEST_ATTR)).thenReturn(null);
        when(req.getInputStream()).thenReturn(emptyInputStream());
        FakeResponse fakeResponse = new FakeResponse();
        CheckEmptyBranchFilter filter = new CheckEmptyBranchFilter();

        filter.doHttpFilter(req, fakeResponse.mock);

        assertFalse(fakeResponse.committed.get());
    }
}
