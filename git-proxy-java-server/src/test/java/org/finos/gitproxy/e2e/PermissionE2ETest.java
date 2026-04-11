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
import org.finos.gitproxy.permission.InMemoryRepoPermissionStore;
import org.finos.gitproxy.permission.RepoPermission;
import org.finos.gitproxy.permission.RepoPermissionService;
import org.finos.gitproxy.service.PushIdentityResolver;
import org.finos.gitproxy.user.ReadOnlyUserStore;
import org.finos.gitproxy.user.StaticUserStore;
import org.finos.gitproxy.user.UserEntry;
import org.junit.jupiter.api.*;

/**
 * End-to-end tests for the repository permission system in transparent proxy mode.
 *
 * <p>Uses a simple username-lookup resolver (no external SCM API calls) paired with {@link InMemoryRepoPermissionStore}
 * to exercise {@link org.finos.gitproxy.servlet.filter.CheckUserPushPermissionFilter} without any external SCM API
 * calls.
 *
 * <p>Credentials in the clone/push URL are forwarded to upstream Gitea, so they must be valid Gitea credentials. The
 * "authorized" user is {@link GiteaContainer#TEST_USER} (created in Gitea and added as a collaborator). The "unlinked"
 * user uses admin credentials — valid for Gitea, but not registered in the proxy user store.
 *
 * <p>Tests cover:
 *
 * <ul>
 *   <li>Literal path grants — exact {@code /owner/repo} match
 *   <li>Glob path grants — {@code /owner/*} wildcard
 *   <li>Regex path grants — full Java regex against the path
 *   <li>Fail-closed semantics — no grant → push blocked
 *   <li>Unregistered user → push blocked with "Identity Not Linked"
 * </ul>
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PermissionE2ETest {

    static GiteaContainer gitea;
    static JettyProxyFixture proxy;
    static InMemoryRepoPermissionStore permissionStore;
    static Path tempDir;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        gitea = new GiteaContainer();
        gitea.start();
        gitea.createAdminUser();
        gitea.createTestRepo();
        // Create a non-admin user and grant write access so they can push via the proxy
        gitea.createTestUser();
        gitea.addTestUserAsCollaborator();

        permissionStore = new InMemoryRepoPermissionStore();
        var permissionService = new RepoPermissionService(permissionStore);

        // Only TEST_USER is registered in the proxy user store — admin is intentionally absent (unlinked)
        var userStore = new StaticUserStore(List.of(UserEntry.builder()
                .username(GiteaContainer.TEST_USER)
                .emails(List.of(GiteaContainer.VALID_AUTHOR_EMAIL))
                .scmIdentities(List.of())
                .build()));
        var identityResolver = usernameResolver(userStore);

        proxy = new JettyProxyFixture(gitea.getBaseUri(), UiApprovalGateway::new, identityResolver, permissionService);
        tempDir = Files.createTempDirectory("git-proxy-java-perm-e2e-");
    }

    @AfterAll
    static void stopInfrastructure() throws Exception {
        if (proxy != null) proxy.close();
        if (gitea != null) gitea.stop();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Test-only resolver: maps HTTP Basic-auth username directly to a proxy user (no SCM API call). */
    private static PushIdentityResolver usernameResolver(ReadOnlyUserStore store) {
        return (provider, pushUsername, token) ->
                pushUsername != null && !pushUsername.isBlank() ? store.findByUsername(pushUsername) : Optional.empty();
    }

    /** URL with {@link GiteaContainer#TEST_USER} credentials — the registered proxy user. */
    private String authorisedUrl() {
        String creds = URLEncoder.encode(GiteaContainer.TEST_USER, StandardCharsets.UTF_8)
                + ":"
                + URLEncoder.encode(GiteaContainer.TEST_USER_PASSWORD, StandardCharsets.UTF_8);
        return "http://" + creds + "@localhost:" + proxy.getPort()
                + "/proxy/localhost/"
                + GiteaContainer.TEST_ORG + "/" + GiteaContainer.TEST_REPO + ".git";
    }

    /**
     * URL with admin credentials — valid for Gitea authentication but the admin user is NOT registered in the proxy
     * user store, so it is treated as an "unlinked" identity.
     */
    private String unlinkedUrl() {
        String creds = URLEncoder.encode(GiteaContainer.ADMIN_USER, StandardCharsets.UTF_8)
                + ":"
                + URLEncoder.encode(GiteaContainer.ADMIN_PASSWORD, StandardCharsets.UTF_8);
        return "http://" + creds + "@localhost:" + proxy.getPort()
                + "/proxy/localhost/"
                + GiteaContainer.TEST_ORG + "/" + GiteaContainer.TEST_REPO + ".git";
    }

    private GitHelper.PushResult cloneCommitPush(String url, String dirSuffix) throws Exception {
        GitHelper git = new GitHelper(tempDir);
        Path repo = git.clone(url, dirSuffix);
        git.setAuthor(repo, GiteaContainer.VALID_AUTHOR_NAME, GiteaContainer.VALID_AUTHOR_EMAIL);
        git.writeAndStage(repo, "test-file.txt", dirSuffix + " - " + Instant.now());
        git.commit(repo, "feat: permission test commit");
        return git.pushWithResult(repo);
    }

    // ── tests ─────────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void noGrant_registeredUser_blocked() throws Exception {
        // No grants in the store at all — fail-closed should block even a registered user.
        var result = cloneCommitPush(authorisedUrl(), "perm-no-grant");
        assertFalse(result.succeeded(), "push should be blocked when no grants exist (fail-closed)");
        assertTrue(
                result.output().contains("not allowed to push")
                        || result.output().contains("Unauthorized"),
                "output should indicate authorization failure. Output:\n" + result.output());
    }

    @Test
    @Order(2)
    void unlinkedUser_blocked_with_identity_not_linked() throws Exception {
        var result = cloneCommitPush(unlinkedUrl(), "perm-unlinked");
        assertFalse(result.succeeded(), "push should be blocked for unlinked user");
        assertTrue(
                result.output().contains("Identity Not Linked"),
                "output should indicate identity not linked. Output:\n" + result.output());
    }

    @Test
    @Order(10)
    void literal_grant_allows_push() throws Exception {
        String path = "/" + GiteaContainer.TEST_ORG + "/" + GiteaContainer.TEST_REPO;
        permissionStore.save(RepoPermission.builder()
                .username(GiteaContainer.TEST_USER)
                .provider(proxy.getProviderId())
                .path(path)
                .pathType(RepoPermission.PathType.LITERAL)
                .operations(RepoPermission.Operations.PUSH)
                .build());

        var result = cloneCommitPush(authorisedUrl(), "perm-literal");
        // Permission passes → push is valid → blocked pending review (not rejected outright)
        assertFalse(result.succeeded(), "valid push should be blocked pending review (not rejected)");
        assertDoesNotThrow(
                result::extractPushId, "a pending-review block should contain a push ID. Output:\n" + result.output());
    }

    @Test
    @Order(11)
    void glob_grant_allows_push() throws Exception {
        // Replace literal grant with a glob covering all repos under TEST_ORG
        permissionStore.findAll().stream()
                .filter(p -> GiteaContainer.TEST_USER.equals(p.getUsername()))
                .forEach(p -> permissionStore.delete(p.getId()));

        permissionStore.save(RepoPermission.builder()
                .username(GiteaContainer.TEST_USER)
                .provider(proxy.getProviderId())
                .path("/" + GiteaContainer.TEST_ORG + "/*")
                .pathType(RepoPermission.PathType.GLOB)
                .operations(RepoPermission.Operations.PUSH)
                .build());

        var result = cloneCommitPush(authorisedUrl(), "perm-glob");
        assertFalse(result.succeeded(), "valid push should be blocked pending review");
        assertDoesNotThrow(
                result::extractPushId,
                "should be blocked pending review (not auth error). Output:\n" + result.output());
    }

    @Test
    @Order(12)
    void regex_grant_allows_push() throws Exception {
        permissionStore.findAll().stream()
                .filter(p -> GiteaContainer.TEST_USER.equals(p.getUsername()))
                .forEach(p -> permissionStore.delete(p.getId()));

        permissionStore.save(RepoPermission.builder()
                .username(GiteaContainer.TEST_USER)
                .provider(proxy.getProviderId())
                .path("^/" + GiteaContainer.TEST_ORG + "/.+")
                .pathType(RepoPermission.PathType.REGEX)
                .operations(RepoPermission.Operations.PUSH)
                .build());

        var result = cloneCommitPush(authorisedUrl(), "perm-regex");
        assertFalse(result.succeeded(), "valid push should be blocked pending review");
        assertDoesNotThrow(
                result::extractPushId,
                "should be blocked pending review (not auth error). Output:\n" + result.output());
    }

    @Test
    @Order(20)
    void glob_grant_does_not_match_different_owner() throws Exception {
        permissionStore.findAll().stream()
                .filter(p -> GiteaContainer.TEST_USER.equals(p.getUsername()))
                .forEach(p -> permissionStore.delete(p.getId()));

        // Grant only for "other-owner/*" — should not match TEST_ORG
        permissionStore.save(RepoPermission.builder()
                .username(GiteaContainer.TEST_USER)
                .provider(proxy.getProviderId())
                .path("/other-owner/*")
                .pathType(RepoPermission.PathType.GLOB)
                .operations(RepoPermission.Operations.PUSH)
                .build());

        var result = cloneCommitPush(authorisedUrl(), "perm-glob-wrong-owner");
        assertFalse(result.succeeded(), "push should be blocked — grant is for a different owner");
        assertFalse(
                result.output().contains("pending review"),
                "should be an auth denial, not a pending-review block. Output:\n" + result.output());
    }
}
