package org.finos.gitproxy.e2e;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
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
 * verifies the re-push succeeds - matching the transparent proxy approval flow.
 *
 * <p>Infrastructure is started once per class (containers are expensive) and each test clones into its own temp
 * directory so there are no ordering dependencies.
 */
@Tag("e2e")
class ProxyModeE2ETest {

    static GiteaContainer gitea;
    static JettyProxyFixture proxy;
    static Path tempDir;

    // Per-test repo — isolates local clone cache and Gitea state between tests.
    String repoName;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        gitea = new GiteaContainer();
        gitea.start();
        gitea.createAdminUser();
        gitea.createTestRepo(); // creates the test-owner org; the per-test repos are added below

        proxy = new JettyProxyFixture(gitea.getBaseUri());
        tempDir = Files.createTempDirectory("git-proxy-java-proxy-e2e-");
    }

    @AfterAll
    static void stopInfrastructure() throws Exception {
        if (proxy != null) proxy.close();
        if (gitea != null) gitea.stop();
    }

    @BeforeEach
    void createRepo() throws Exception {
        repoName = "proxy-e2e-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        gitea.createRepo(GiteaContainer.TEST_ORG, repoName);
    }

    // ---- helpers ----

    /** Returns a proxy clone URL with admin credentials embedded. */
    private String repoUrl() {
        String creds = URLEncoder.encode(GiteaContainer.ADMIN_USER, StandardCharsets.UTF_8)
                + ":"
                + URLEncoder.encode(GiteaContainer.ADMIN_PASSWORD, StandardCharsets.UTF_8);
        return "http://" + creds + "@localhost:" + proxy.getPort()
                + "/proxy/localhost/"
                + GiteaContainer.TEST_ORG + "/" + repoName + ".git";
    }

    private GitHelper helper() {
        return new GitHelper(tempDir);
    }

    private PushStore pushStore() {
        return proxy.getPushStore();
    }

    /** Clones, commits, and pushes. Returns the push result (exit code + output) so the caller can assert on it. */
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

        // First push - should be blocked pending review
        var firstPush = git.pushWithResult(repo);
        assertFalse(firstPush.succeeded(), "first push should be blocked pending review");
        assertTrue(firstPush.output().contains("/push/"), "should contain link to push record");

        String pushId = firstPush.extractPushId();
        assertNotNull(pushId, "push ID should be present in blocked message");

        // Verify push record was persisted as PENDING
        var record = pushStore().findById(pushId);
        assertTrue(record.isPresent(), "push record should exist in store");
        assertEquals(PushStatus.PENDING, record.get().getStatus(), "push should be PENDING review");

        // Approve the push
        pushStore()
                .approve(
                        pushId,
                        Attestation.builder()
                                .pushId(pushId)
                                .type(Attestation.Type.APPROVAL)
                                .reviewerUsername("e2e-test-reviewer")
                                .reason("Approved by e2e test")
                                .build());

        // Re-push - should succeed now
        var rePush = git.pushWithResult(repo);
        assertTrue(rePush.succeeded(), "re-push after approval should succeed. Output:\n" + rePush.output());
    }

    // ---- passing tests (mirrors test-proxy-pass.sh) ----

    @Test
    void cleanCommit_validEmail_blockedThenApproved() throws Exception {
        pushApproveAndVerify(
                "proxy-pass-1", GiteaContainer.VALID_AUTHOR_EMAIL, "feat: add new feature for proxy filter testing");
    }

    @Test
    void multipleCleanCommits_blockedThenApproved() throws Exception {
        GitHelper git = helper();
        Path repo = git.clone(repoUrl(), "proxy-pass-multi");
        git.setAuthor(repo, GiteaContainer.VALID_AUTHOR_NAME, GiteaContainer.VALID_AUTHOR_EMAIL);

        git.writeAndStage(repo, "test-a.txt", "pass test 2a - " + Instant.now());
        git.commit(repo, "docs: update documentation");

        git.writeAndStage(repo, "test-b.txt", "pass test 2b - " + Instant.now());
        git.commit(repo, "refactor: clean up internal logic");

        // First push - blocked
        var firstPush = git.pushWithResult(repo);
        assertFalse(firstPush.succeeded(), "first push should be blocked");
        String pushId = firstPush.extractPushId();

        // Approve
        pushStore()
                .approve(
                        pushId,
                        Attestation.builder()
                                .pushId(pushId)
                                .type(Attestation.Type.APPROVAL)
                                .reviewerUsername("e2e-test-reviewer")
                                .reason("Approved by e2e test")
                                .build());

        // Re-push - should succeed
        var rePush = git.pushWithResult(repo);
        assertTrue(rePush.succeeded(), "re-push should succeed. Output:\n" + rePush.output());
    }

    // ---- failing tests (mirrors test-proxy-fail.sh) ----
    // These pushes should be REJECTED by validation - they never enter the approval queue.

    @Test
    void noreplyLocalPart_rejected() throws Exception {
        var result = cloneCommitPush("proxy-fail-noreply", "noreply@example.com", "feat: noreply author");
        assertFalse(result.succeeded(), "push with noreply@ should be rejected");
        assertTrue(result.output().contains("validation issue(s)"), "should contain validation failure summary");
    }

    @Test
    void noReplyHyphenLocalPart_rejected() throws Exception {
        var result = cloneCommitPush("proxy-fail-noreply2", "no-reply@example.com", "feat: no-reply local part");
        assertFalse(result.succeeded(), "push with no-reply@ should be rejected");
    }

    @Test
    void nonAllowedEmailDomain_rejected() throws Exception {
        var result = cloneCommitPush("proxy-fail-domain", "developer@internal.corp.net", "feat: non-allowed domain");
        assertFalse(result.succeeded(), "push with disallowed email domain should be rejected");
    }

    @Test
    void githubNoreplyEmail_rejected() throws Exception {
        var result = cloneCommitPush(
                "proxy-fail-ghnoreply", "12345+user@users.noreply.github.com", "feat: GitHub noreply email");
        assertFalse(result.succeeded(), "push with GitHub noreply email should be rejected");
    }

    @Test
    void wipCommitMessage_rejected() throws Exception {
        var result = cloneCommitPush("proxy-fail-wip", GiteaContainer.VALID_AUTHOR_EMAIL, "WIP: still working on this");
        assertFalse(result.succeeded(), "push with WIP message should be rejected");
    }

    @Test
    void fixupCommitMessage_rejected() throws Exception {
        var result = cloneCommitPush(
                "proxy-fail-fixup", GiteaContainer.VALID_AUTHOR_EMAIL, "fixup! previous commit that needs squashing");
        assertFalse(result.succeeded(), "push with fixup! message should be rejected");
    }

    @Test
    void doNotMergeCommitMessage_rejected() throws Exception {
        var result = cloneCommitPush(
                "proxy-fail-dnm", GiteaContainer.VALID_AUTHOR_EMAIL, "DO NOT MERGE - experimental branch");
        assertFalse(result.succeeded(), "push with DO NOT MERGE message should be rejected");
    }

    @Test
    void passwordInCommitMessage_rejected() throws Exception {
        var result = cloneCommitPush(
                "proxy-fail-password",
                GiteaContainer.VALID_AUTHOR_EMAIL,
                "fix: update config where password= hunter2 was exposed");
        assertFalse(result.succeeded(), "push with password= in message should be rejected");
    }

    @Test
    void tokenInCommitMessage_rejected() throws Exception {
        var result = cloneCommitPush(
                "proxy-fail-token",
                GiteaContainer.VALID_AUTHOR_EMAIL,
                "chore: rotate token=ghp_abc123def456 in CI config");
        assertFalse(result.succeeded(), "push with token= in message should be rejected");
    }

    // ---- checkEmptyBranch (mirrors checkEmptyBranch.ts) ----

    @Test
    void emptyBranch_rejected() throws Exception {
        // The Gitea repo is auto-initialised with a README, so main already has a commit.
        // Cloning and creating a new branch at HEAD (no new commits) means the branch tip
        // is already reachable from main - getCommitRange returns empty → rejected outright.
        GitHelper git = helper();
        Path repo = git.clone(repoUrl(), "proxy-empty-branch");
        git.setAuthor(repo, GiteaContainer.VALID_AUTHOR_NAME, GiteaContainer.VALID_AUTHOR_EMAIL);
        git.createAndCheckoutBranch(repo, "proxy-empty-test-branch");

        var result = git.pushWithResult(repo);
        assertFalse(result.succeeded(), "push of branch with no new commits should be rejected");
        assertTrue(
                result.output().contains("Empty Branch"),
                "rejection should identify the empty branch condition. Output:\n" + result.output());
        assertTrue(
                result.output().contains("commit before pushing"),
                "rejection message should mention making a commit. Output:\n" + result.output());
    }

    // ---- checkHiddenCommits (mirrors checkHiddenCommits.ts) ----
    //
    // The "hidden commits" failure case (pack containing commits outside the push range) cannot
    // be reproduced with a standard git client: git only includes objects reachable from the
    // pushed tip in the pack, and those are always a subset of the introduced commit range.
    // The check is a defensive measure against maliciously crafted packs; it is covered by the
    // passing tests above (which confirm the filter does not disrupt normal pushes).

    // ---- tag push tests ----

    @Test
    void tagPush_blockedThenApproved() throws Exception {
        GitHelper git = helper();
        Path repo = git.clone(repoUrl(), "proxy-tag-annotated");
        git.setAuthor(repo, GiteaContainer.VALID_AUTHOR_NAME, GiteaContainer.VALID_AUTHOR_EMAIL);

        git.writeAndStage(repo, "tag-test.txt", "proxy tag test - " + Instant.now());
        git.commit(repo, "feat: commit for proxy tag test");

        // Push the branch first (blocked → approve → re-push)
        var branchPush = git.pushWithResult(repo);
        assertFalse(branchPush.succeeded(), "branch push should be blocked pending review");
        String branchPushId = branchPush.extractPushId();
        pushStore()
                .approve(
                        branchPushId,
                        Attestation.builder()
                                .pushId(branchPushId)
                                .type(Attestation.Type.APPROVAL)
                                .reviewerUsername("e2e-test-reviewer")
                                .reason("Approved for tag test")
                                .build());
        assertTrue(git.pushWithResult(repo).succeeded(), "branch re-push should succeed");

        // Tag the commit and push the tag
        git.annotatedTag(repo, "proxy-v0.1.0", "Release proxy-v0.1.0");
        var tagPush = git.pushRefWithResult(repo, "proxy-v0.1.0");
        assertFalse(tagPush.succeeded(), "tag push should be blocked pending review");

        String tagPushId = tagPush.extractPushId();
        pushStore()
                .approve(
                        tagPushId,
                        Attestation.builder()
                                .pushId(tagPushId)
                                .type(Attestation.Type.APPROVAL)
                                .reviewerUsername("e2e-test-reviewer")
                                .reason("Approved tag")
                                .build());

        var tagRePush = git.pushRefWithResult(repo, "proxy-v0.1.0");
        assertTrue(tagRePush.succeeded(), "tag re-push after approval should succeed. Output:\n" + tagRePush.output());
    }
}
