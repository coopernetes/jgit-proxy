package org.finos.gitproxy.e2e;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.*;

/**
 * End-to-end tests for the <em>transparent proxy</em> path ({@code /proxy/...}).
 *
 * <p>Mirrors {@code test-proxy-pass.sh} and {@code test-proxy-fail.sh}: every test performs a real {@code git clone} +
 * commit + push through a live Jetty proxy that forwards to a containerised Gitea instance.
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

    /**
     * Clones the test repo, sets the given author identity, appends a timestamped line to a test file, stages and
     * commits with {@code message}, then attempts a push.
     *
     * @return {@code true} if the push succeeded
     */
    private boolean cloneCommitPush(String dirSuffix, String authorEmail, String commitMessage) throws Exception {
        GitHelper git = helper();
        Path repo = git.clone(repoUrl(), dirSuffix);
        git.setAuthor(repo, GiteaContainer.VALID_AUTHOR_NAME, authorEmail);
        git.writeAndStage(repo, "test-file.txt", commitMessage + " - " + Instant.now());
        git.commit(repo, commitMessage);
        return git.tryPush(repo);
    }

    // ---- passing tests (mirrors test-proxy-pass.sh) ----

    @Test
    @Order(1)
    void cleanCommit_validEmail_passes() throws Exception {
        assertTrue(
                cloneCommitPush(
                        "proxy-pass-1",
                        GiteaContainer.VALID_AUTHOR_EMAIL,
                        "feat: add new feature for proxy filter testing"),
                "push should succeed with clean message and valid email");
    }

    @Test
    @Order(2)
    void multipleCleanCommits_pass() throws Exception {
        GitHelper git = helper();
        Path repo = git.clone(repoUrl(), "proxy-pass-multi");
        git.setAuthor(repo, GiteaContainer.VALID_AUTHOR_NAME, GiteaContainer.VALID_AUTHOR_EMAIL);

        git.writeAndStage(repo, "test-a.txt", "pass test 2a - " + Instant.now());
        git.commit(repo, "docs: update documentation");

        git.writeAndStage(repo, "test-b.txt", "pass test 2b - " + Instant.now());
        git.commit(repo, "refactor: clean up internal logic");

        assertTrue(git.tryPush(repo), "multi-commit push should succeed");
    }

    // ---- failing tests (mirrors test-proxy-fail.sh) ----

    @Test
    @Order(10)
    void noreplyLocalPart_blocked() throws Exception {
        assertFalse(
                cloneCommitPush("proxy-fail-noreply", "noreply@example.com", "feat: this commit has a noreply author"),
                "push with noreply@ address should be rejected");
    }

    @Test
    @Order(11)
    void noReplyHyphenLocalPart_blocked() throws Exception {
        assertFalse(
                cloneCommitPush("proxy-fail-noreply2", "no-reply@example.com", "feat: no-reply local part"),
                "push with no-reply@ address should be rejected");
    }

    @Test
    @Order(12)
    void nonAllowedEmailDomain_blocked() throws Exception {
        assertFalse(
                cloneCommitPush(
                        "proxy-fail-domain",
                        "developer@internal.corp.net",
                        "feat: this commit has a non-allowed domain"),
                "push with disallowed email domain should be rejected");
    }

    @Test
    @Order(13)
    void githubNoreplyEmail_blocked() throws Exception {
        assertFalse(
                cloneCommitPush(
                        "proxy-fail-ghnoreply",
                        "12345+user@users.noreply.github.com",
                        "feat: this commit uses GitHub noreply email"),
                "push with GitHub noreply email should be rejected");
    }

    @Test
    @Order(20)
    void wipCommitMessage_blocked() throws Exception {
        assertFalse(
                cloneCommitPush(
                        "proxy-fail-wip", GiteaContainer.VALID_AUTHOR_EMAIL, "WIP: still working on this feature"),
                "push with WIP commit message should be rejected");
    }

    @Test
    @Order(21)
    void fixupCommitMessage_blocked() throws Exception {
        assertFalse(
                cloneCommitPush(
                        "proxy-fail-fixup",
                        GiteaContainer.VALID_AUTHOR_EMAIL,
                        "fixup! previous commit that needs squashing"),
                "push with fixup! message should be rejected");
    }

    @Test
    @Order(22)
    void doNotMergeCommitMessage_blocked() throws Exception {
        assertFalse(
                cloneCommitPush(
                        "proxy-fail-dnm", GiteaContainer.VALID_AUTHOR_EMAIL, "DO NOT MERGE - experimental branch"),
                "push with DO NOT MERGE message should be rejected");
    }

    @Test
    @Order(23)
    void passwordInCommitMessage_blocked() throws Exception {
        assertFalse(
                cloneCommitPush(
                        "proxy-fail-password",
                        GiteaContainer.VALID_AUTHOR_EMAIL,
                        "fix: update config where password= hunter2 was exposed"),
                "push with password= in message should be rejected");
    }

    @Test
    @Order(24)
    void tokenInCommitMessage_blocked() throws Exception {
        assertFalse(
                cloneCommitPush(
                        "proxy-fail-token",
                        GiteaContainer.VALID_AUTHOR_EMAIL,
                        "chore: rotate token=ghp_abc123def456 in CI config"),
                "push with token= in message should be rejected");
    }
}
