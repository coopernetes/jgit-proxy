package org.finos.gitproxy.e2e;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.finos.gitproxy.approval.AutoApprovalGateway;
import org.junit.jupiter.api.*;

/**
 * End-to-end tests for the <em>transparent proxy</em> path ({@code /proxy/...}) in <em>auto-approve</em> mode
 * ({@code server.approval-mode: auto}).
 *
 * <p>In this mode clean pushes are immediately forwarded without human review. Validation failures (bad email, bad
 * message, etc.) are still rejected — the approval mode only affects what happens to pushes that pass all content
 * checks.
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProxyModeAutoApproveE2ETest {

    static GiteaContainer gitea;
    static JettyProxyFixture proxy;
    static Path tempDir;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        gitea = new GiteaContainer();
        gitea.start();
        gitea.createAdminUser();
        gitea.createTestRepo();

        proxy = new JettyProxyFixture(gitea.getBaseUri(), AutoApprovalGateway::new);
        tempDir = Files.createTempDirectory("jgit-proxy-proxy-auto-e2e-");
    }

    @AfterAll
    static void stopInfrastructure() throws Exception {
        if (proxy != null) proxy.close();
        if (gitea != null) gitea.stop();
    }

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

    private GitHelper.PushResult cloneCommitPush(String dirSuffix, String authorEmail, String commitMessage)
            throws Exception {
        GitHelper git = helper();
        Path repo = git.clone(repoUrl(), dirSuffix);
        git.setAuthor(repo, GiteaContainer.VALID_AUTHOR_NAME, authorEmail);
        git.writeAndStage(repo, "test-file.txt", commitMessage + " - " + Instant.now());
        git.commit(repo, commitMessage);
        return git.pushWithResult(repo);
    }

    // ---- passing tests: clean pushes go through without blocking ----

    @Test
    @Order(1)
    void cleanCommit_validEmail_succeedsImmediately() throws Exception {
        var result = cloneCommitPush(
                "proxy-auto-pass-1",
                GiteaContainer.VALID_AUTHOR_EMAIL,
                "feat: first clean commit in auto-approve mode");
        assertTrue(
                result.succeeded(), "clean push should succeed immediately in auto mode. Output:\n" + result.output());
    }

    @Test
    @Order(2)
    void multipleCleanCommits_succeedImmediately() throws Exception {
        GitHelper git = helper();
        Path repo = git.clone(repoUrl(), "proxy-auto-pass-multi");
        git.setAuthor(repo, GiteaContainer.VALID_AUTHOR_NAME, GiteaContainer.VALID_AUTHOR_EMAIL);

        git.writeAndStage(repo, "test-a.txt", "auto-approve test 2a - " + Instant.now());
        git.commit(repo, "docs: update readme");

        git.writeAndStage(repo, "test-b.txt", "auto-approve test 2b - " + Instant.now());
        git.commit(repo, "refactor: tidy up helpers");

        var result = git.pushWithResult(repo);
        assertTrue(
                result.succeeded(),
                "multi-commit push should succeed immediately in auto mode. Output:\n" + result.output());
    }

    // ---- failing tests: validation rejects still apply regardless of approval mode ----

    @Test
    @Order(10)
    void noreplyEmail_rejectedByValidation() throws Exception {
        var result =
                cloneCommitPush("proxy-auto-fail-noreply", "noreply@example.com", "feat: noreply author in auto mode");
        assertFalse(result.succeeded(), "noreply@ address should be rejected even in auto mode");
    }

    @Test
    @Order(11)
    void wipCommitMessage_rejectedByValidation() throws Exception {
        var result =
                cloneCommitPush("proxy-auto-fail-wip", GiteaContainer.VALID_AUTHOR_EMAIL, "WIP: please do not merge");
        assertFalse(result.succeeded(), "WIP commit message should be rejected even in auto mode");
    }
}
