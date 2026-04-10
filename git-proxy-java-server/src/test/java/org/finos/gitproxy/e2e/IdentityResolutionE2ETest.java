package org.finos.gitproxy.e2e;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.finos.gitproxy.approval.UiApprovalGateway;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.db.model.Attestation;
import org.finos.gitproxy.permission.InMemoryRepoPermissionStore;
import org.finos.gitproxy.permission.RepoPermission;
import org.finos.gitproxy.permission.RepoPermissionService;
import org.finos.gitproxy.service.PushIdentityResolver;
import org.finos.gitproxy.servlet.filter.IdentityVerificationFilter;
import org.finos.gitproxy.user.ReadOnlyUserStore;
import org.finos.gitproxy.user.StaticUserStore;
import org.finos.gitproxy.user.UserEntry;
import org.junit.jupiter.api.*;

/**
 * End-to-end tests for identity resolution in transparent proxy mode.
 *
 * <p>Two scenarios are covered, mirroring {@code test/demo-proxy-identity.sh}:
 *
 * <ul>
 *   <li><strong>Scenario A — Linked user</strong>: HTTP Basic-auth username maps to a registered proxy account. The
 *       push is blocked pending review (not rejected by identity check), and can be approved.
 *   <li><strong>Scenario B — Unlinked user</strong>: HTTP Basic-auth username is not in the user store. The push is
 *       rejected immediately with an "Identity Not Linked" error.
 * </ul>
 *
 * <p>A third scenario validates {@link IdentityVerificationFilter} in STRICT mode: commit email must match a registered
 * email for the push user, otherwise the push is rejected.
 *
 * <p>Uses a simple username-lookup resolver (no external SCM API calls) to map HTTP Basic-auth usernames to proxy
 * users. Credentials in the clone URL must be valid Gitea credentials: the linked user is
 * {@link GiteaContainer#TEST_USER} (a real Gitea user with collaborator access). The unlinked user uses admin
 * credentials — valid for Gitea but not registered in the proxy user store.
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IdentityResolutionE2ETest {

    static GiteaContainer gitea;
    static JettyProxyFixture proxy;
    static Path tempDir;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        gitea = new GiteaContainer();
        gitea.start();
        gitea.createAdminUser();
        gitea.createTestRepo();
        gitea.createTestUser();
        gitea.addTestUserAsCollaborator();

        var permissionStore = new InMemoryRepoPermissionStore();
        // Seed permission grant for the linked user
        permissionStore.save(RepoPermission.builder()
                .username(GiteaContainer.TEST_USER)
                .provider("gitea-e2e")
                .path("/" + GiteaContainer.TEST_ORG + "/" + GiteaContainer.TEST_REPO)
                .pathType(RepoPermission.PathType.LITERAL)
                .operations(RepoPermission.Operations.PUSH)
                .build());

        // Only TEST_USER is in the proxy store — admin is intentionally absent (unlinked)
        var userStore = new StaticUserStore(List.of(UserEntry.builder()
                .username(GiteaContainer.TEST_USER)
                .emails(List.of(GiteaContainer.TEST_USER_EMAIL))
                .scmIdentities(List.of())
                .build()));
        var identityResolver = usernameResolver(userStore);

        proxy = new JettyProxyFixture(
                gitea.getBaseUri(),
                UiApprovalGateway::new,
                identityResolver,
                new RepoPermissionService(permissionStore));
        tempDir = Files.createTempDirectory("git-proxy-java-ident-e2e-");
    }

    @AfterAll
    static void stopInfrastructure() throws Exception {
        if (proxy != null) proxy.close();
        if (gitea != null) gitea.stop();
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    /** Test-only resolver: maps HTTP Basic-auth username directly to a proxy user (no SCM API call). */
    private static PushIdentityResolver usernameResolver(ReadOnlyUserStore store) {
        return (provider, pushUsername, token) ->
                pushUsername != null && !pushUsername.isBlank() ? store.findByUsername(pushUsername) : Optional.empty();
    }

    private String linkedUrl() {
        String creds = URLEncoder.encode(GiteaContainer.TEST_USER, StandardCharsets.UTF_8)
                + ":"
                + URLEncoder.encode(GiteaContainer.TEST_USER_PASSWORD, StandardCharsets.UTF_8);
        return "http://" + creds + "@localhost:" + proxy.getPort()
                + "/proxy/localhost/"
                + GiteaContainer.TEST_ORG + "/" + GiteaContainer.TEST_REPO + ".git";
    }

    /**
     * Admin credentials are valid for Gitea but the admin user is NOT registered in the proxy user store, so every push
     * using them is treated as "unlinked".
     */
    private String unlinkedUrl() {
        String creds = URLEncoder.encode(GiteaContainer.ADMIN_USER, StandardCharsets.UTF_8)
                + ":"
                + URLEncoder.encode(GiteaContainer.ADMIN_PASSWORD, StandardCharsets.UTF_8);
        return "http://" + creds + "@localhost:" + proxy.getPort()
                + "/proxy/localhost/"
                + GiteaContainer.TEST_ORG + "/" + GiteaContainer.TEST_REPO + ".git";
    }

    // ── Scenario A: linked user ───────────────────────────────────────────────────

    @Test
    @Order(1)
    void linkedUser_blockedPendingReview_then_approved() throws Exception {
        GitHelper git = new GitHelper(tempDir);
        Path repo = git.clone(linkedUrl(), "ident-linked");
        // Commit with the registered email — identity check should pass
        git.setAuthor(repo, GiteaContainer.VALID_AUTHOR_NAME, GiteaContainer.TEST_USER_EMAIL);
        git.writeAndStage(repo, "test-file.txt", "identity test - " + Instant.now());
        git.commit(repo, "feat: identity demo - linked user");

        // First push: clean → blocked pending review, not rejected outright
        var firstPush = git.pushWithResult(repo);
        assertFalse(firstPush.succeeded(), "first push should be blocked pending review");
        String pushId = firstPush.extractPushId();

        assertTrue(proxy.getPushStore().findById(pushId).isPresent(), "push record should exist in store");

        // Approve and re-push
        proxy.getPushStore()
                .approve(
                        pushId,
                        Attestation.builder()
                                .pushId(pushId)
                                .type(Attestation.Type.APPROVAL)
                                .reviewerUsername("e2e-reviewer")
                                .reason("identity demo approval")
                                .build());

        var rePush = git.pushWithResult(repo);
        assertTrue(rePush.succeeded(), "re-push after approval should succeed. Output:\n" + rePush.output());
    }

    // ── Scenario B: unlinked user ─────────────────────────────────────────────────

    @Test
    @Order(2)
    void unlinkedUser_blocked_with_identity_not_linked() throws Exception {
        GitHelper git = new GitHelper(tempDir);
        Path repo = git.clone(unlinkedUrl(), "ident-unlinked");
        git.setAuthor(repo, "Unlinked Developer", GiteaContainer.VALID_AUTHOR_EMAIL);
        git.writeAndStage(repo, "test-file.txt", "unlinked identity test - " + Instant.now());
        git.commit(repo, "feat: identity demo - unlinked user");

        var result = git.pushWithResult(repo);
        assertFalse(result.succeeded(), "push by unlinked user should be blocked");
        assertTrue(
                result.output().contains("Identity Not Linked"),
                "output should mention 'Identity Not Linked'. Output:\n" + result.output());
    }

    // ── Scenario C: email mismatch (STRICT mode) ──────────────────────────────────

    @Test
    @Order(3)
    void linkedUser_wrongCommitEmail_strictMode_rejected() throws Exception {
        var strictPermissionStore = new InMemoryRepoPermissionStore();
        strictPermissionStore.save(RepoPermission.builder()
                .username(GiteaContainer.TEST_USER)
                .provider("gitea-e2e")
                .path("/" + GiteaContainer.TEST_ORG + "/" + GiteaContainer.TEST_REPO)
                .pathType(RepoPermission.PathType.LITERAL)
                .operations(RepoPermission.Operations.PUSH)
                .build());
        var strictUserStore = new StaticUserStore(List.of(UserEntry.builder()
                .username(GiteaContainer.TEST_USER)
                .emails(List.of(GiteaContainer.TEST_USER_EMAIL))
                .scmIdentities(List.of())
                .build()));
        var strictResolver = usernameResolver(strictUserStore);

        try (var strictProxy = new JettyProxyFixture(
                gitea.getBaseUri(),
                UiApprovalGateway::new,
                strictResolver,
                new RepoPermissionService(strictPermissionStore),
                CommitConfig.IdentityVerificationMode.STRICT)) {

            String url = URLEncoder.encode(GiteaContainer.TEST_USER, StandardCharsets.UTF_8)
                    + ":"
                    + URLEncoder.encode(GiteaContainer.TEST_USER_PASSWORD, StandardCharsets.UTF_8);
            url = "http://" + url + "@localhost:" + strictProxy.getPort()
                    + "/proxy/localhost/"
                    + GiteaContainer.TEST_ORG + "/" + GiteaContainer.TEST_REPO + ".git";

            GitHelper git = new GitHelper(tempDir);
            Path repo = git.clone(url, "ident-wrong-email");
            // Commit with an email NOT registered to TEST_USER
            git.setAuthor(repo, "Test Dev", "wrong-email@other-domain.com");
            git.writeAndStage(repo, "test-file.txt", "email mismatch test - " + Instant.now());
            git.commit(repo, "feat: commit from mismatched email");

            var result = git.pushWithResult(repo);
            assertFalse(result.succeeded(), "push with mismatched commit email should be rejected in STRICT mode");
            assertTrue(
                    result.output().toLowerCase().contains("identity"),
                    "output should mention identity issue. Output:\n" + result.output());
        }
    }
}
