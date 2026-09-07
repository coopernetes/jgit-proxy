package com.rbc.fogwall.servlet.filter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rbc.fogwall.git.GitClientUtils;
import com.rbc.fogwall.git.GitRequestDetails;
import com.rbc.fogwall.git.HttpOperation;
import com.rbc.fogwall.provider.GitHubProvider;
import com.rbc.fogwall.servlet.RequestBodyWrapper;
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

    // ---- shallow clone push (single ref preceded by shallow pkt-lines) ----

    @Test
    void parse_shallowClonePush_oneShallowLine_isNotRejected() throws Exception {
        byte[] existingBody = loadResource("push-sample-01-body.bin");
        byte[] packData = extractPackData(existingBody);

        byte[] body = buildBody(
                new String[] {"shallow " + PUSH1_OLD, PUSH1_OLD + " " + PUSH1_NEW + " " + PUSH1_REF + "\0 report-status"
                },
                packData);

        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");
        GitRequestDetails details = makeFilter().parse(wrapper);

        assertNotEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        assertEquals(PUSH1_REF, details.getBranch());
        assertEquals(PUSH1_OLD, details.getCommitFrom());
        assertEquals(PUSH1_NEW, details.getCommitTo());
    }

    @Test
    void parse_shallowClonePush_multipleShallowLines_isNotRejected() throws Exception {
        byte[] existingBody = loadResource("push-sample-01-body.bin");
        byte[] packData = extractPackData(existingBody);

        byte[] body = buildBody(
                new String[] {
                    "shallow " + PUSH1_OLD,
                    "shallow " + PUSH1_NEW,
                    PUSH1_OLD + " " + PUSH1_NEW + " " + PUSH1_REF + "\0 report-status"
                },
                packData);

        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");
        GitRequestDetails details = makeFilter().parse(wrapper);

        assertNotEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        assertEquals(PUSH1_REF, details.getBranch());
        assertEquals(PUSH1_OLD, details.getCommitFrom());
        assertEquals(PUSH1_NEW, details.getCommitTo());
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
    // ---- malformed ref update lines (object ids must be 40-hex; body must be parseable) ----

    @Test
    void parse_nonHexObjectIds_isRejected() throws Exception {
        // Values shaped like paths must never enter push records or JGit resolve() calls.
        byte[] body = buildBody(new String[] {"../ /../.data refs/heads/main\0 report-status"}, "PACK".getBytes());

        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");
        GitRequestDetails details = makeFilter().parse(wrapper);

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        assertNull(details.getCommitFrom(), "Malformed object ids must not be recorded");
        assertNull(details.getCommitTo(), "Malformed object ids must not be recorded");
    }

    @Test
    void parse_shortObjectId_isRejected() throws Exception {
        String shortOid = PUSH1_NEW.substring(0, 39);
        byte[] body = buildBody(
                new String[] {PUSH1_OLD + " " + shortOid + " refs/heads/main\0 report-status"}, "PACK".getBytes());

        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");
        GitRequestDetails details = makeFilter().parse(wrapper);

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
    }

    @Test
    void parse_spacelessRefUpdateLine_isRejectedNotThrown() throws Exception {
        // Previously threw ArrayIndexOutOfBoundsException → HTTP 500 with a PENDING record left behind.
        byte[] body = buildBody(new String[] {"no-spaces-here"}, "PACK".getBytes());

        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");
        GitRequestDetails details = assertDoesNotThrow(() -> makeFilter().parse(wrapper));

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
    }

    @Test
    void parse_unparseableBody_isRejectedNotPending() throws Exception {
        // Not pkt-line framed at all. The push must be rejected with an accurate reason, not left
        // PENDING for downstream filters to happen to stop.
        byte[] body = "zzzz-this-is-not-a-git-push-body".getBytes();

        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");
        GitRequestDetails details = makeFilter().parse(wrapper);

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        assertTrue(details.getReason().contains("could not be parsed"), "Reason must name the parse failure");
    }

    @Test
    void parse_zeroOidBranchCreate_isAccepted() throws Exception {
        byte[] existingBody = loadResource("push-sample-01-body.bin");
        byte[] packData = extractPackData(existingBody);
        byte[] body = buildBody(
                new String[] {GitClientUtils.ZERO_OID + " " + PUSH1_NEW + " refs/heads/new-branch\0 report-status"},
                packData);

        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");
        GitRequestDetails details = makeFilter().parse(wrapper);

        assertNotEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        assertEquals(GitClientUtils.ZERO_OID, details.getCommitFrom());
        assertEquals(PUSH1_NEW, details.getCommitTo());
    }

    // ---- signed pushes (push-cert blocks are rejected by name, not misdiagnosed) ----

    @Test
    void parse_signedPush_isRejectedWithAccurateReason() throws Exception {
        // git push --signed sends a push-cert block instead of a bare ref-update line. It must be
        // rejected as unsupported, not misreported as a multi-branch push.
        byte[] body = buildBody(
                new String[] {
                    "push-cert\0 report-status agent=git/2.46.0",
                    "certificate version 0.1",
                    "pusher A U Thor <author@example.com> 1700000000 +0000",
                    "pushee https://fogwall.example.com/owner/repo.git",
                    "nonce 1700000000-abcdef",
                    "",
                    PUSH1_OLD + " " + PUSH1_NEW + " " + PUSH1_REF,
                    "-----BEGIN PGP SIGNATURE-----",
                    "-----END PGP SIGNATURE-----",
                    "push-cert-end"
                },
                "PACK".getBytes());

        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");
        GitRequestDetails details = makeFilter().parse(wrapper);

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        assertTrue(details.getReason().contains("--signed"), "Reason must name signed pushes, not multi-branch");
        assertNull(details.getCommitFrom(), "Nothing from the certificate may enter the push record");
        assertNull(details.getCommitTo(), "Nothing from the certificate may enter the push record");
    }

    @Test
    void parse_branchNamedPushCert_isNotMistakenForSignedPush() throws Exception {
        // A ref update line always starts with a 40-hex object id, so a branch that happens to be
        // called push-cert must still parse as a normal push.
        byte[] existingBody = loadResource("push-sample-01-body.bin");
        byte[] packData = extractPackData(existingBody);
        byte[] body = buildBody(
                new String[] {PUSH1_OLD + " " + PUSH1_NEW + " refs/heads/push-cert\0 report-status"}, packData);

        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");
        GitRequestDetails details = makeFilter().parse(wrapper);

        assertNotEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        assertEquals("refs/heads/push-cert", details.getBranch());
    }

    // ---- push options (git push -o is rejected at the capability, never relayed) ----

    @Test
    void parse_pushWithPushOptions_isRejectedByName() throws Exception {
        // git push -o sends option lines between the ref-update flush and the pack once the server
        // has advertised push-options. The proxy cannot inspect or strip them, so the request is
        // refused as soon as the capability shows up on the ref-update line.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PacketLineOut plo = new PacketLineOut(out);
        plo.writeString(
                PUSH1_OLD + " " + PUSH1_NEW + " " + PUSH1_REF + "\0 report-status push-options agent=git/2.46.0");
        plo.end();
        plo.writeString("merge_request.create");
        plo.writeString("repo.private=false");
        plo.end();
        out.write(extractPackData(loadResource("push-sample-01-body.bin")));

        RequestBodyWrapper wrapper = wrapBody(out.toByteArray(), "/owner/repo.git/git-receive-pack");
        GitRequestDetails details = makeFilter().parse(wrapper);

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        assertTrue(details.getRejectionTitle().contains("Push Options"), "Title must name push options");
        assertTrue(details.getReason().contains("-o"), "Reason must tell the user to drop -o");
        assertNull(details.getCommitTo(), "A rejected push must not populate the push record");
    }

    @Test
    void parse_pushOptionsCapabilityAlone_isRejectedEvenWithoutOptionLines() throws Exception {
        // Negotiating the capability is enough: an empty option list still means the upstream
        // would accept options, and the proxy has no way to tell the body was clean.
        byte[] packData = extractPackData(loadResource("push-sample-01-body.bin"));
        byte[] body = buildBody(
                new String[] {PUSH1_OLD + " " + PUSH1_NEW + " " + PUSH1_REF + "\0 report-status push-options"},
                packData);

        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");
        GitRequestDetails details = makeFilter().parse(wrapper);

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        assertTrue(details.getRejectionTitle().contains("Push Options"), "Title must name push options");
    }

    @Test
    void parse_branchNamedPushOptions_isNotMistakenForPushOptions() throws Exception {
        // The capability check reads only the list after the NUL, so a ref or agent string that
        // merely contains the words must still parse as a normal push.
        byte[] packData = extractPackData(loadResource("push-sample-01-body.bin"));
        byte[] body = buildBody(
                new String[] {
                    PUSH1_OLD + " " + PUSH1_NEW + " refs/heads/push-options\0 report-status agent=push-options-client"
                },
                packData);

        RequestBodyWrapper wrapper = wrapBody(body, "/owner/repo.git/git-receive-pack");
        GitRequestDetails details = makeFilter().parse(wrapper);

        assertNotEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        assertEquals("refs/heads/push-options", details.getBranch());
    }

    // ---- invalid repository path segments (rejected before any body parsing) ----

    @Test
    void parse_traversalOwnerSegment_isRejected() throws Exception {
        byte[] body = loadResource("push-sample-01-body.bin");
        RequestBodyWrapper wrapper = wrapBody(body, "/../repo.git/git-receive-pack");

        GitRequestDetails details = makeFilter().parse(wrapper);

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        assertTrue(details.getReason().contains("owner and name"), "Reason should describe the allowed characters");
        assertNull(details.getCommitFrom(), "Body must not be parsed for a rejected path");
    }

    @Test
    void parse_dotGitStrippedNameCollapsingToTraversal_isRejected() throws Exception {
        byte[] body = loadResource("push-sample-01-body.bin");
        // "...git" passes a naive charset check but strips to "." — the semantic value must be validated
        RequestBodyWrapper wrapper = wrapBody(body, "/owner/...git/git-receive-pack");

        GitRequestDetails details = makeFilter().parse(wrapper);

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
    }

    @Test
    void parse_controlCharactersInOwner_isRejected() throws Exception {
        byte[] body = loadResource("push-sample-01-body.bin");
        RequestBodyWrapper wrapper = wrapBody(body, "/own\r\ner/repo.git/git-receive-pack");

        GitRequestDetails details = makeFilter().parse(wrapper);

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
    }

    @Test
    void parse_validPath_isNotRejectedByPathValidation() throws Exception {
        byte[] body = loadResource("push-sample-01-body.bin");
        RequestBodyWrapper wrapper = wrapBody(body, "/my-org/my.repo_2.git/git-receive-pack");

        GitRequestDetails details = makeFilter().parse(wrapper);

        assertNotEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        assertEquals("my-org", details.getRepoRef().getOwner());
        assertEquals("my.repo_2", details.getRepoRef().getName());
    }

    // ---- nested group paths (GitLab subgroups) ----

    @Test
    void parse_nestedGroupPath_keepsEverySegment() throws Exception {
        byte[] body = loadResource("push-sample-01-body.bin");
        RequestBodyWrapper wrapper = wrapBody(body, "/group/subgroup/project.git/git-receive-pack");

        GitRequestDetails details = makeFilter().parse(wrapper);

        assertNotEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        assertEquals("group/subgroup", details.getRepoRef().getOwner());
        assertEquals("project", details.getRepoRef().getName());
        assertEquals("/group/subgroup/project", details.getRepoRef().getSlug());
    }

    @Test
    void parse_traversalInsideNestedOwner_isRejected() throws Exception {
        byte[] body = loadResource("push-sample-01-body.bin");
        RequestBodyWrapper wrapper = wrapBody(body, "/group/../escaped/project.git/git-receive-pack");

        GitRequestDetails details = makeFilter().parse(wrapper);

        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        assertNull(details.getCommitFrom(), "Body must not be parsed for a rejected path");
    }
}
