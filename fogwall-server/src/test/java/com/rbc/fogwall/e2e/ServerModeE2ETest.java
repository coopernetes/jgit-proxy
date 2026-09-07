package com.rbc.fogwall.e2e;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.*;

/**
 * End-to-end tests for the <em>server mode</em> path ({@code /push/...}).
 *
 * <p>Mirrors {@code test-push-pass.sh} and {@code test-push-fail.sh}: every test performs a real {@code git clone} +
 * commit + push through a live Jetty proxy that uses JGit's ReceivePack to receive the push, runs pre-receive
 * validation hooks, and (on success) forwards to a containerised Gitea instance.
 *
 * <p>Infrastructure is shared across the class. Each test clones into its own temp directory so there are no ordering
 * dependencies.
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ServerModeE2ETest {

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
        tempDir = Files.createTempDirectory("fogwall-sf-e2e-");
    }

    @AfterAll
    static void stopInfrastructure() throws Exception {
        if (proxy != null) proxy.close();
        if (gitea != null) gitea.stop();
    }

    // ---- helpers ----

    /**
     * Push URL with admin credentials embedded. Gitea uses these to authenticate the upstream push after validation
     * passes.
     */
    private String repoUrl() {
        return repoUrlFor(proxy);
    }

    /** Server mode ({@code /push/…}) repo URL with admin credentials embedded, for an arbitrary fixture. */
    private String repoUrlFor(JettyProxyFixture fixture) {
        String creds = URLEncoder.encode(GiteaContainer.ADMIN_USER, StandardCharsets.UTF_8)
                + ":"
                + URLEncoder.encode(GiteaContainer.ADMIN_PASSWORD, StandardCharsets.UTF_8);
        return "http://" + creds + "@localhost:" + fixture.getPort()
                + "/push/" + fixture.getGiteaHostPort() + "/"
                + GiteaContainer.TEST_ORG + "/" + GiteaContainer.TEST_REPO + ".git";
    }

    /**
     * Clones the test repo via the push path, sets author identity, writes a timestamped file, commits with
     * {@code message}, and pushes.
     *
     * @return {@code true} if the push exited 0
     */
    private boolean cloneCommitPush(String dirSuffix, String authorEmail, String commitMessage) throws Exception {
        GitHelper git = new GitHelper(tempDir);
        Path repo = git.clone(repoUrl(), dirSuffix);
        git.setAuthor(repo, GiteaContainer.VALID_AUTHOR_NAME, authorEmail);
        git.writeAndStage(repo, "test-file.txt", commitMessage + " - " + Instant.now());
        git.commit(repo, commitMessage);
        return git.tryPush(repo);
    }

    // ---- passing tests (mirrors test-push-pass.sh) ----

    @Test
    @Order(1)
    void cleanCommit_validEmail_passes() throws Exception {
        assertTrue(
                cloneCommitPush(
                        "sf-pass-1",
                        GiteaContainer.VALID_AUTHOR_EMAIL,
                        "feat: add new feature for server mode testing"),
                "push should succeed with clean message and valid email");
    }

    @Test
    @Order(2)
    void multipleCleanCommits_pass() throws Exception {
        GitHelper git = new GitHelper(tempDir);
        Path repo = git.clone(repoUrl(), "sf-pass-multi");
        git.setAuthor(repo, GiteaContainer.VALID_AUTHOR_NAME, GiteaContainer.VALID_AUTHOR_EMAIL);

        git.writeAndStage(repo, "test-a.txt", "pass test 2a - " + Instant.now());
        git.commit(repo, "docs: update documentation");

        git.writeAndStage(repo, "test-b.txt", "pass test 2b - " + Instant.now());
        git.commit(repo, "refactor: clean up internal logic");

        assertTrue(git.tryPush(repo), "multi-commit push should succeed");
    }

    // ---- fetch toggle (#478) ----

    @Test
    @Order(3)
    void fetchEnabled_cloneSucceeds() throws Exception {
        // The shared fixture runs with serve-fetch on (the default): server mode serves clone/fetch.
        GitHelper git = new GitHelper(tempDir);
        var result = git.cloneWithResult(repoUrl(), "sf-fetch-enabled");
        assertTrue(
                result.succeeded(),
                "clone should succeed when serve-fetch is enabled (default). Output:\n" + result.output());
    }

    @Test
    @Order(4)
    void fetchDisabled_cloneRefusedButPushStillWorks() throws Exception {
        // A separate fixture against the same Gitea with serve-fetch turned off — server mode is push-only.
        try (JettyProxyFixture noFetch = new JettyProxyFixture(gitea.getBaseUri(), false)) {
            GitHelper git = new GitHelper(tempDir);

            // Clone/fetch is refused with a clear git-side message, not a 404 that reads as a missing repo.
            var cloneResult = git.cloneWithResult(repoUrlFor(noFetch), "sf-nofetch-clone");
            assertFalse(cloneResult.succeeded(), "clone should be refused when serve-fetch is off");
            assertTrue(
                    cloneResult.output().contains("fetches are not served through this gateway"),
                    "refusal should carry the clear gateway message. Output:\n" + cloneResult.output());

            // Push (receive-pack) is unaffected: get a working copy via the fetch-enabled shared proxy, then
            // push it to the fetch-disabled gateway.
            Path repo = git.clone(repoUrl(), "sf-nofetch-push");
            git.setAuthor(repo, GiteaContainer.VALID_AUTHOR_NAME, GiteaContainer.VALID_AUTHOR_EMAIL);
            git.setRemoteUrl(repo, "origin", repoUrlFor(noFetch));
            git.writeAndStage(repo, "nofetch.txt", "push works with fetch disabled - " + Instant.now());
            git.commit(repo, "feat: push still works when fetch serving is disabled");
            assertTrue(git.tryPush(repo), "push should succeed even when serve-fetch is off (receive-pack unaffected)");
        }
    }

    // ---- failing tests (mirrors test-push-fail.sh) ----

    @Test
    @Order(10)
    void noreplyLocalPart_blocked() throws Exception {
        assertFalse(
                cloneCommitPush("sf-fail-noreply", "noreply@example.com", "feat: this commit has a noreply author"),
                "push with noreply@ address should be rejected");
    }

    @Test
    @Order(11)
    void noReplyHyphenLocalPart_blocked() throws Exception {
        assertFalse(
                cloneCommitPush("sf-fail-noreply2", "no-reply@example.com", "feat: no-reply local part"),
                "push with no-reply@ address should be rejected");
    }

    @Test
    @Order(12)
    void nonAllowedEmailDomain_blocked() throws Exception {
        assertFalse(
                cloneCommitPush(
                        "sf-fail-domain", "developer@internal.corp.net", "feat: this commit has a non-allowed domain"),
                "push with disallowed email domain should be rejected");
    }

    @Test
    @Order(13)
    void githubNoreplyEmail_blocked() throws Exception {
        assertFalse(
                cloneCommitPush(
                        "sf-fail-ghnoreply",
                        "12345+user@users.noreply.github.com",
                        "feat: this commit uses GitHub noreply email"),
                "push with GitHub noreply email should be rejected");
    }

    @Test
    @Order(20)
    void wipCommitMessage_blocked() throws Exception {
        assertFalse(
                cloneCommitPush("sf-fail-wip", GiteaContainer.VALID_AUTHOR_EMAIL, "WIP: still working on this feature"),
                "push with WIP commit message should be rejected");
    }

    @Test
    @Order(21)
    void fixupCommitMessage_blocked() throws Exception {
        assertFalse(
                cloneCommitPush(
                        "sf-fail-fixup",
                        GiteaContainer.VALID_AUTHOR_EMAIL,
                        "fixup! previous commit that needs squashing"),
                "push with fixup! message should be rejected");
    }

    @Test
    @Order(22)
    void doNotMergeCommitMessage_blocked() throws Exception {
        assertFalse(
                cloneCommitPush("sf-fail-dnm", GiteaContainer.VALID_AUTHOR_EMAIL, "DO NOT MERGE - experimental branch"),
                "push with DO NOT MERGE message should be rejected");
    }

    @Test
    @Order(23)
    void passwordInCommitMessage_blocked() throws Exception {
        assertFalse(
                cloneCommitPush(
                        "sf-fail-password",
                        GiteaContainer.VALID_AUTHOR_EMAIL,
                        "fix: update config where password= hunter2 was exposed"),
                "push with password= in message should be rejected");
    }

    @Test
    @Order(24)
    void tokenInCommitMessage_blocked() throws Exception {
        assertFalse(
                cloneCommitPush(
                        "sf-fail-token",
                        GiteaContainer.VALID_AUTHOR_EMAIL,
                        "chore: rotate token=ghp_abc123def456 in CI config"),
                "push with token= in message should be rejected");
    }

    // ---- checkEmptyBranch (mirrors checkEmptyBranch.ts) ----

    @Test
    @Order(50)
    void emptyBranch_blocked() throws Exception {
        // The Gitea repo is auto-initialised with a README, so main already has a commit.
        // Cloning and creating a new branch at HEAD (no new commits) means the branch tip
        // is already reachable from main - getCommitRange returns empty → rejected.
        GitHelper git = new GitHelper(tempDir);
        Path repo = git.clone(repoUrl(), "sf-empty-branch");
        git.setAuthor(repo, GiteaContainer.VALID_AUTHOR_NAME, GiteaContainer.VALID_AUTHOR_EMAIL);
        git.createAndCheckoutBranch(repo, "sf-empty-test-branch");

        var result = git.pushWithResult(repo);
        assertFalse(result.succeeded(), "push of branch with no new commits should be rejected");
        assertTrue(
                result.output().contains("commit before pushing"),
                "rejection message should mention making a commit. Output:\n" + result.output());
    }

    // ---- push options ----

    @Test
    @Order(55)
    void pushOptions_refusedAtNegotiation() throws Exception {
        // Server mode never advertises push-options, so the client itself aborts before sending a
        // pack: the option can neither reach a hook nor be forwarded upstream.
        GitHelper git = new GitHelper(tempDir);
        Path repo = git.clone(repoUrl(), "sf-push-options");
        git.setAuthor(repo, GiteaContainer.VALID_AUTHOR_NAME, GiteaContainer.VALID_AUTHOR_EMAIL);
        git.writeAndStage(repo, "test-file.txt", "push options - " + Instant.now());
        git.commit(repo, "feat: push with an option");

        var result = git.pushWithResult(repo, "-o", "repo.private=false");
        assertFalse(result.succeeded(), "push carrying a push option should be refused");
        assertTrue(
                result.output().contains("does not support push options"),
                "git should report the capability as unsupported. Output:\n" + result.output());
    }

    // ---- checkHiddenCommits (mirrors checkHiddenCommits.ts) ----
    //
    // The "hidden commits" failure case (pack containing commits outside the push range) cannot
    // be reproduced with a standard git client: git only includes objects reachable from the
    // pushed tip in the pack, and those are always a subset of the introduced commit range.
    // The check is a defensive measure against maliciously crafted packs; it is covered by the
    // passing tests above (which confirm the hook does not disrupt normal pushes).

    // ---- tag push tests ----

    @Test
    @Order(60)
    void lightweightTagPush_passes() throws Exception {
        GitHelper git = new GitHelper(tempDir);
        Path repo = git.clone(repoUrl(), "sf-tag-lightweight");
        git.setAuthor(repo, GiteaContainer.VALID_AUTHOR_NAME, GiteaContainer.VALID_AUTHOR_EMAIL);

        git.writeAndStage(repo, "tag-test.txt", "lightweight tag test - " + Instant.now());
        git.commit(repo, "feat: commit for lightweight tag test");
        assertTrue(git.tryPush(repo), "branch push before tagging should succeed");

        // Tag the commit and push the tag
        git.lightweightTag(repo, "sf-v0.1.0");
        assertTrue(git.tryPushRef(repo, "sf-v0.1.0"), "lightweight tag push should succeed");
    }

    @Test
    @Order(61)
    void annotatedTagPush_passes() throws Exception {
        GitHelper git = new GitHelper(tempDir);
        Path repo = git.clone(repoUrl(), "sf-tag-annotated");
        git.setAuthor(repo, GiteaContainer.VALID_AUTHOR_NAME, GiteaContainer.VALID_AUTHOR_EMAIL);

        git.writeAndStage(repo, "tag-test.txt", "annotated tag test - " + Instant.now());
        git.commit(repo, "feat: commit for annotated tag test");
        assertTrue(git.tryPush(repo), "branch push before tagging should succeed");

        git.annotatedTag(repo, "sf-v0.2.0", "Release sf-v0.2.0");
        assertTrue(git.tryPushRef(repo, "sf-v0.2.0"), "annotated tag push should succeed");
    }

    // ---- Identity resolution tests ----

    @Test
    @Order(100)
    @Disabled("requires user store configuration with identity verification enabled")
    void resolvedUser_isPopulatedOnPushRecord() throws Exception {
        // This test validates that when a push is made by a user with a registered SCM identity
        // (configured in fogwall-local.yml), the push record's resolvedUser field is populated.
        // Requires: dev1 user with dev1-gh SCM identity for the test provider.
        // Expected: resolvedUser == "dev1" on the persisted push record.
        assertTrue(
                cloneCommitPush(
                        "sf-identity-resolved", GiteaContainer.VALID_AUTHOR_EMAIL, "feat: test identity resolution"),
                "push by registered user should succeed");
        // TODO: Add assertion to fetch the push record from the store and verify
        // pushStore.findById(pushId).get().getResolvedUser() is not null
    }

    @Test
    @Order(101)
    @Disabled("requires user store configuration with identity verification enabled")
    void unregisteredUser_isBlockedWhenUserStoreConfigured() throws Exception {
        // This test validates that when a push is attempted by an unregistered user,
        // and the proxy has identity verification enabled, the push is blocked.
        // Requires: configured user store with identity verification.
        // Expected: push fails with message mentioning user not registered.
        assertFalse(
                cloneCommitPush(
                        "sf-identity-unregistered",
                        GiteaContainer.VALID_AUTHOR_EMAIL,
                        "feat: attempt by unregistered user"),
                "push by unregistered user should be blocked");
    }
}
