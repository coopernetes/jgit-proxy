package org.finos.gitproxy.servlet.filter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Enumeration;
import org.eclipse.jgit.transport.PacketLineOut;
import org.finos.gitproxy.git.GitClientUtils;
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
    void parse_pushRequest_extractsSlugWithLeadingSlash() throws Exception {
        byte[] body = loadResource("push-sample-01-body.bin");
        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");

        GitRequestDetails details = makeFilter().parse(wrapper);

        assertNotNull(details.getRepoRef());
        assertEquals("/owner/repo", details.getRepoRef().getSlug());
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

    // ---- CVE-2025-54583: multi-ref push rejection ----

    /**
     * Build a raw git receive-pack request body from pkt-line strings and optional pack data. Uses JGit's
     * {@link PacketLineOut} to produce correctly framed pkt-lines.
     */
    private byte[] buildBody(String[] packetLines, byte[] packData) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PacketLineOut plo = new PacketLineOut(out);
        for (String line : packetLines) {
            plo.writeString(line);
        }
        plo.end(); // flush packet (0000)
        if (packData != null && packData.length > 0) {
            out.write(packData);
        }
        return out.toByteArray();
    }

    /** Extract the raw PACK bytes (starting with 'P','A','C','K') from a captured body resource. */
    private byte[] extractPackData(byte[] fullBody) {
        for (int i = 0; i < fullBody.length - 4; i++) {
            if (fullBody[i] == 'P' && fullBody[i + 1] == 'A' && fullBody[i + 2] == 'C' && fullBody[i + 3] == 'K') {
                byte[] pack = new byte[fullBody.length - i];
                System.arraycopy(fullBody, i, pack, 0, pack.length);
                return pack;
            }
        }
        throw new IllegalArgumentException("No PACK signature found in body");
    }

    @Test
    void parse_multiRefPush_isRejected() throws Exception {
        byte[] existingBody = loadResource("push-sample-01-body.bin");
        byte[] packData = extractPackData(existingBody);

        byte[] body = buildBody(
                new String[] {
                    PUSH1_OLD + " " + PUSH1_NEW + " " + PUSH1_REF + "\0 report-status",
                    PUSH1_NEW + " " + PUSH2_NEW + " refs/heads/feature"
                },
                packData);

        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");
        GitRequestDetails details = makeFilter().parse(wrapper);

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        assertTrue(details.getReason().contains("one branch"), "Reason should mention single-branch requirement");
    }

    @Test
    void parse_multiRefPush_doesNotPopulateCommitFields() throws Exception {
        byte[] body = buildBody(
                new String[] {
                    PUSH1_OLD + " " + PUSH1_NEW + " " + PUSH1_REF + "\0 report-status",
                    PUSH1_NEW + " " + PUSH2_NEW + " refs/heads/feature"
                },
                "PACK".getBytes());

        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");
        GitRequestDetails details = makeFilter().parse(wrapper);

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        assertNull(details.getCommitFrom(), "commitFrom must not be populated on rejected multi-ref push");
        assertNull(details.getCommitTo(), "commitTo must not be populated on rejected multi-ref push");
        assertNull(details.getBranch(), "branch must not be populated on rejected multi-ref push");
    }

    @Test
    void parse_threeRefPush_isRejected() throws Exception {
        byte[] body = buildBody(
                new String[] {
                    PUSH1_OLD + " " + PUSH1_NEW + " refs/heads/main\0 report-status",
                    PUSH1_NEW + " " + PUSH2_NEW + " refs/heads/feature",
                    GitClientUtils.ZERO_OID + " " + PUSH1_NEW + " refs/heads/new-branch"
                },
                "PACK".getBytes());

        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");
        GitRequestDetails details = makeFilter().parse(wrapper);

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
    }

    // ---- CVE-2025-54584: PACK signature in ref name ----

    @Test
    void parse_refNameContainingPACK_parsesSuccessfully() throws Exception {
        byte[] existingBody = loadResource("push-sample-01-body.bin");
        byte[] packData = extractPackData(existingBody);

        // Build a body where the ref name contains the bytes "PACK"
        byte[] body = buildBody(
                new String[] {PUSH1_OLD + " " + PUSH1_NEW + " refs/heads/PACK-evil\0 report-status"}, packData);

        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");
        GitRequestDetails details = makeFilter().parse(wrapper);

        // Should NOT be rejected — single ref is fine
        assertNotEquals(
                GitRequestDetails.GitResult.REJECTED,
                details.getResult(),
                "Single-ref push must not be rejected even with PACK in ref name");
        assertEquals("refs/heads/PACK-evil", details.getBranch());
        assertEquals(PUSH1_OLD, details.getCommitFrom());
        assertEquals(PUSH1_NEW, details.getCommitTo());
    }

    @Test
    void parse_refNameContainingPACK_commitStillParsed() throws Exception {
        byte[] existingBody = loadResource("push-sample-01-body.bin");
        byte[] packData = extractPackData(existingBody);

        byte[] body = buildBody(
                new String[] {PUSH1_OLD + " " + PUSH1_NEW + " refs/heads/PACK-evil\0 report-status"}, packData);

        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");
        GitRequestDetails details = makeFilter().parse(wrapper);

        assertNotNull(details.getCommit(), "Commit should be parsed despite PACK in ref name");
    }

    // ---- single-ref push still works after changes ----

    @Test
    void parse_singleRefPush_isNotRejected() throws Exception {
        byte[] body = loadResource("push-sample-01-body.bin");
        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");

        GitRequestDetails details = makeFilter().parse(wrapper);

        assertNotEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        assertEquals(PUSH1_REF, details.getBranch());
        assertEquals(PUSH1_OLD, details.getCommitFrom());
        assertEquals(PUSH1_NEW, details.getCommitTo());
    }

    // ---- branch deletion (single ref, newOid = 0000...) ----

    @Test
    void parse_branchDeletion_isNotRejected() throws Exception {
        byte[] body = buildBody(
                new String[] {PUSH1_OLD + " " + GitClientUtils.ZERO_OID + " refs/heads/feature\0 report-status"},
                new byte[0]);

        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");
        GitRequestDetails details = makeFilter().parse(wrapper);

        assertNotEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        assertEquals("refs/heads/feature", details.getBranch());
        assertEquals(GitClientUtils.ZERO_OID, details.getCommitTo());
    }

    // ---- tag push (single ref) ----

    @Test
    void parse_tagPush_isNotRejected() throws Exception {
        byte[] existingBody = loadResource("push-sample-01-body.bin");
        byte[] packData = extractPackData(existingBody);

        byte[] body = buildBody(
                new String[] {GitClientUtils.ZERO_OID + " " + PUSH1_NEW + " refs/tags/v1.0\0 report-status"}, packData);

        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");
        GitRequestDetails details = makeFilter().parse(wrapper);

        assertNotEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        assertEquals("refs/tags/v1.0", details.getBranch());
    }
}
