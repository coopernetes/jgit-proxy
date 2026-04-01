package org.finos.gitproxy.e2e;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.model.Attestation;
import org.finos.gitproxy.db.model.PushStatus;
import org.junit.jupiter.api.*;

/**
 * End-to-end tests for the <em>transparent proxy</em> path ({@code /proxy/...}).
 *
 * <p>Mirrors {@code test-proxy-pass.sh} and {@code test-proxy-fail.sh}: every test performs a real {@code git clone} +
 * commit + push through a live Jetty proxy that forwards to a containerised Gitea instance.
 *
 * <p>Valid pushes are blocked pending review on first push. The test then approves the push via the push store and
 * verifies the re-push succeeds — matching the transparent proxy approval flow.
 *
 * <p>Infrastructure is started once per class (containers are expensive) and each test clones into its own temp
 * directory so there are no ordering dependencies.
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProxyModeE2ETest {

    static GiteaContainer gitea;
    static JettyProxyFixture proxy;
    static Path tempDir;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        gitea = new GiteaContainer();
        gitea.start();
        gitea.createAdminUser();
        gitea.createTestRepo();

        proxy = new JettyProxyFixture(gitea.getBaseUri());
        tempDir = Files.createTempDirectory("jgit-proxy-proxy-e2e-");
    }

    @AfterAll
    static void stopInfrastructure() throws Exception {
        if (proxy != null) proxy.close();
        if (gitea != null) gitea.stop();
    }

    // ---- helpers ----

    /** Returns a proxy clone URL with admin credentials embedded. */
    private String repoUrl() {
        String creds = URLEncoder.encode(GiteaContainer.ADMIN_USER, StandardCharsets.UTF_8)
                + ":"
                + URLEncoder.encode(GiteaContainer.ADMIN_PASSWORD, StandardCharsets.UTF_8);
        return "http://" + creds + "@localhost:" + proxy.getPort()
                + "/proxy/localhost/"
                + GiteaContainer.TEST_ORG + "/" + GiteaContainer.TEST_REPO + ".git";
    }

    private GitHelper helper() {
        return new GitHelper(tempDir);
    }

    private PushStore pushStore() {
        return proxy.getPushStore();
    }

    /**
     * Clones, commits, and pushes. Returns the push result (exit code + output) so the caller can assert on it.
     */
    private GitHelper.PushResult cloneCommitPush(String dirSuffix, String authorEmail, String commitMessage)
            throws Exception {
        GitHelper git = helper();
        Path repo = git.clone(repoUrl(), dirSuffix);
        git.setAuthor(repo, GiteaContainer.VALID_AUTHOR_NAME, authorEmail);
        git.writeAndStage(repo, "test-file.txt", commitMessage + " - " + Instant.now());
        git.commit(repo, commitMessage);
        return git.pushWithResult(repo);
    }

    /**
     * Push → assert blocked → approve → re-push → assert success. This is the standard approval flow for valid pushes
     * in transparent proxy mode.
     */
    private void pushApproveAndVerify(String dirSuffix, String authorEmail, String commitMessage) throws Exception {
        GitHelper git = helper();
        Path repo = git.clone(repoUrl(), dirSuffix);
        git.setAuthor(repo, GiteaContainer.VALID_AUTHOR_NAME, authorEmail);
        git.writeAndStage(repo, "test-file.txt", commitMessage + " - " + Instant.now());
        git.commit(repo, commitMessage);

        // First push — should be blocked pending review
        var firstPush = git.pushWithResult(repo);
        assertFalse(firstPush.succeeded(), "first push should be blocked pending review");
        assertTrue(firstPush.output().contains("pending push"), "should contain pending push link");

        String pushId = firstPush.extractPushId();
        assertNotNull(pushId, "push ID should be present in blocked message");

        // Verify push record was persisted as BLOCKED
        var record = pushStore().findById(pushId);
        assertTrue(record.isPresent(), "push record should exist in store");
        assertEquals(PushStatus.BLOCKED, record.get().getStatus(), "push should be BLOCKED pending review");

        // Approve the push
        pushStore().approve(pushId, Attestation.builder()
                .pushId(pushId)
                .type(Attestation.Type.APPROVAL)
                .reviewerUsername("e2e-test-reviewer")
                .reason("Approved by e2e test")
                .build());

        // Re-push — should succeed now
        var rePush = git.pushWithResult(repo);
        assertTrue(rePush.succeeded(), "re-push after approval should succeed. Output:\n" + rePush.output());
    }

    // ---- passing tests (mirrors test-proxy-pass.sh) ----

    @Test
    @Order(1)
    void cleanCommit_validEmail_blockedThenApproved() throws Exception {
        pushApproveAndVerify(
                "proxy-pass-1", GiteaContainer.VALID_AUTHOR_EMAIL, "feat: add new feature for proxy filter testing");
    }

    @Test
    @Order(2)
    void multipleCleanCommits_blockedThenApproved() throws Exception {
        GitHelper git = helper();
        Path repo = git.clone(repoUrl(), "proxy-pass-multi");
        git.setAuthor(repo, GiteaContainer.VALID_AUTHOR_NAME, GiteaContainer.VALID_AUTHOR_EMAIL);

        git.writeAndStage(repo, "test-a.txt", "pass test 2a - " + Instant.now());
        git.commit(repo, "docs: update documentation");

        git.writeAndStage(repo, "test-b.txt", "pass test 2b - " + Instant.now());
        git.commit(repo, "refactor: clean up internal logic");

        // First push — blocked
        var firstPush = git.pushWithResult(repo);
        assertFalse(firstPush.succeeded(), "first push should be blocked");
        String pushId = firstPush.extractPushId();

        // Approve
        pushStore().approve(pushId, Attestation.builder()
                .pushId(pushId)
                .type(Attestation.Type.APPROVAL)
                .reviewerUsername("e2e-test-reviewer")
                .reason("Approved by e2e test")
                .build());

        // Re-push — should succeed
        var rePush = git.pushWithResult(repo);
        assertTrue(rePush.succeeded(), "re-push should succeed. Output:\n" + rePush.output());
    }

    // ---- failing tests (mirrors test-proxy-fail.sh) ----
    // These pushes should be REJECTED by validation — they never enter the approval queue.

    @Test
    @Order(10)
    void noreplyLocalPart_rejected() throws Exception {
        var result = cloneCommitPush("proxy-fail-noreply", "noreply@example.com", "feat: noreply author");
        assertFalse(result.succeeded(), "push with noreply@ should be rejected");
        assertTrue(result.output().contains("check(s) failed"), "should contain validation failure summary");
    }

    @Test
    @Order(11)
    void noReplyHyphenLocalPart_rejected() throws Exception {
        var result = cloneCommitPush("proxy-fail-noreply2", "no-reply@example.com", "feat: no-reply local part");
        assertFalse(result.succeeded(), "push with no-reply@ should be rejected");
    }

    @Test
    @Order(12)
    void nonAllowedEmailDomain_rejected() throws Exception {
        var result = cloneCommitPush(
                "proxy-fail-domain", "developer@internal.corp.net", "feat: non-allowed domain");
        assertFalse(result.succeeded(), "push with disallowed email domain should be rejected");
    }

    @Test
    @Order(13)
    void githubNoreplyEmail_rejected() throws Exception {
        var result = cloneCommitPush(
                "proxy-fail-ghnoreply", "12345+user@users.noreply.github.com", "feat: GitHub noreply email");
        assertFalse(result.succeeded(), "push with GitHub noreply email should be rejected");
    }

    @Test
    @Order(20)
    void wipCommitMessage_rejected() throws Exception {
        var result =
                cloneCommitPush("proxy-fail-wip", GiteaContainer.VALID_AUTHOR_EMAIL, "WIP: still working on this");
        assertFalse(result.succeeded(), "push with WIP message should be rejected");
    }

    @Test
    @Order(21)
    void fixupCommitMessage_rejected() throws Exception {
        var result = cloneCommitPush(
                "proxy-fail-fixup", GiteaContainer.VALID_AUTHOR_EMAIL, "fixup! previous commit that needs squashing");
        assertFalse(result.succeeded(), "push with fixup! message should be rejected");
    }

    @Test
    @Order(22)
    void doNotMergeCommitMessage_rejected() throws Exception {
        var result = cloneCommitPush(
                "proxy-fail-dnm", GiteaContainer.VALID_AUTHOR_EMAIL, "DO NOT MERGE - experimental branch");
        assertFalse(result.succeeded(), "push with DO NOT MERGE message should be rejected");
    }

    @Test
    @Order(23)
    void passwordInCommitMessage_rejected() throws Exception {
        var result = cloneCommitPush(
                "proxy-fail-password",
                GiteaContainer.VALID_AUTHOR_EMAIL,
                "fix: update config where password= hunter2 was exposed");
        assertFalse(result.succeeded(), "push with password= in message should be rejected");
    }

    @Test
    @Order(24)
    void tokenInCommitMessage_rejected() throws Exception {
        var result = cloneCommitPush(
                "proxy-fail-token",
                GiteaContainer.VALID_AUTHOR_EMAIL,
                "chore: rotate token=ghp_abc123def456 in CI config");
        assertFalse(result.succeeded(), "push with token= in message should be rejected");
    }
}
