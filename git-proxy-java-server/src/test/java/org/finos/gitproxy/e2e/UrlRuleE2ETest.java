package org.finos.gitproxy.e2e;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.finos.gitproxy.db.model.AccessRule;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.servlet.filter.UrlRuleFilter;
import org.junit.jupiter.api.*;

/**
 * End-to-end tests for URL allow/deny rule enforcement through both the transparent proxy path ({@code /proxy/...}) and
 * the store-and-forward path ({@code /push/...}).
 *
 * <p>Rule set mirrors {@code docker/git-proxy-docker-default.yml}:
 *
 * <ul>
 *   <li>Allow slug {@code /test-owner/test-repo} — PUSH and FETCH
 *   <li>Allow owner glob {@code otherorg/*} — PUSH and FETCH
 *   <li>Deny slug {@code /otherorg/other-secret} — overrides the owner allow (deny wins)
 *   <li>Deny name glob {@code *-readonly} — PUSH only (fetch still allowed)
 *   <li>Deny name regex {@code (?i)(^|-)secret(-|$).*} — PUSH only
 * </ul>
 *
 * <p>Each major rule scenario is validated through both proxy and S&F modes to confirm the shared
 * {@link org.finos.gitproxy.servlet.filter.UrlRuleEvaluator} behaves identically in both.
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UrlRuleE2ETest {

    static GiteaContainer gitea;
    /** Proxy with URL rules configured, auto-approves clean pushes. */
    static JettyProxyFixture proxy;

    static Path tempDir;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        gitea = new GiteaContainer();
        gitea.start();
        gitea.createAdminUser();
        gitea.createTestRepo(); // creates test-owner/test-repo

        // Create additional repos needed for the deny-rule tests.
        // Blocked repos don't actually need to exist on Gitea — the deny fires before any upstream
        // contact — but we create them to keep the Gitea side consistent with the allow-rule tests.
        gitea.createRepo(GiteaContainer.TEST_ORG, "test-repo-readonly");
        gitea.createRepo(GiteaContainer.TEST_ORG, "secret-store");
        gitea.createOrg("otherorg");
        gitea.createRepo("otherorg", "allowed-repo");
        gitea.createRepo("otherorg", "other-secret");

        proxy = new JettyProxyFixture(gitea.getBaseUri(), buildRules(gitea.getBaseUri()));
        tempDir = Files.createTempDirectory("git-proxy-java-urlrule-e2e-");
    }

    @AfterAll
    static void stopInfrastructure() throws Exception {
        if (proxy != null) proxy.close();
        if (gitea != null) gitea.stop();
    }

    // ── URL helpers ──────────────────────────────────────────────────────────

    private String proxyUrl(String org, String repo) {
        String creds = encode(GiteaContainer.ADMIN_USER) + ":" + encode(GiteaContainer.ADMIN_PASSWORD);
        // getPushBase/getProxyBase already have the correct host segment (e.g. /proxy/localhost)
        return proxy.getProxyBase().replace("http://", "http://" + creds + "@") + "/" + org + "/" + repo + ".git";
    }

    private String pushUrl(String org, String repo) {
        String creds = encode(GiteaContainer.ADMIN_USER) + ":" + encode(GiteaContainer.ADMIN_PASSWORD);
        return proxy.getPushBase().replace("http://", "http://" + creds + "@") + "/" + org + "/" + repo + ".git";
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Clone → commit → push through the transparent proxy path. Returns true if push exited 0. */
    private boolean proxyPush(String suffix, String org, String repo, String message) throws Exception {
        return cloneCommitPush(proxyUrl(org, repo), suffix, message);
    }

    /** Clone → commit → push through the store-and-forward path. Returns true if push exited 0. */
    private boolean sfPush(String suffix, String org, String repo, String message) throws Exception {
        return cloneCommitPush(pushUrl(org, repo), suffix, message);
    }

    /** Clone → commit → push, returning the full result (exit code + output). */
    private GitHelper.PushResult proxyPushWithResult(String suffix, String org, String repo, String message)
            throws Exception {
        return cloneCommitPushWithResult(proxyUrl(org, repo), suffix, message);
    }

    private GitHelper.PushResult sfPushWithResult(String suffix, String org, String repo, String message)
            throws Exception {
        return cloneCommitPushWithResult(pushUrl(org, repo), suffix, message);
    }

    private boolean cloneCommitPush(String url, String suffix, String message) throws Exception {
        GitHelper git = new GitHelper(tempDir);
        Path repoDir = git.clone(url, suffix);
        git.setAuthor(repoDir, GiteaContainer.VALID_AUTHOR_NAME, GiteaContainer.VALID_AUTHOR_EMAIL);
        git.writeAndStage(repoDir, "file.txt", message + " - " + Instant.now());
        git.commit(repoDir, message);
        return git.tryPush(repoDir);
    }

    private GitHelper.PushResult cloneCommitPushWithResult(String url, String suffix, String message) throws Exception {
        GitHelper git = new GitHelper(tempDir);
        Path repoDir = git.clone(url, suffix);
        git.setAuthor(repoDir, GiteaContainer.VALID_AUTHOR_NAME, GiteaContainer.VALID_AUTHOR_EMAIL);
        git.writeAndStage(repoDir, "file.txt", message + " - " + Instant.now());
        git.commit(repoDir, message);
        return git.pushWithResult(repoDir);
    }

    /**
     * For push-blocked tests: clone directly from Gitea (bypassing the proxy), make a commit, then attempt the push via
     * the proxy. This avoids the clone failing when the URL rule also blocks FETCH on that repo.
     *
     * <p>For repos that don't exist on Gitea at all (e.g. unknown-org), use the allowed test repo as the clone source
     * and redirect the push remote to the target URL.
     */
    private GitHelper.PushResult cloneFromGiteaThenPushViaProxy(
            String cloneOrg, String cloneRepo, String pushProxyUrl, String suffix, String message) throws Exception {
        // Clone directly from Gitea — no proxy, no URL rule enforcement on clone
        String directUrl = gitea.getBaseUrl() + "/" + cloneOrg + "/" + cloneRepo + ".git";
        String directWithCreds = directUrl.replace(
                "http://",
                "http://" + encode(GiteaContainer.ADMIN_USER) + ":" + encode(GiteaContainer.ADMIN_PASSWORD) + "@");
        GitHelper git = new GitHelper(tempDir);
        Path repoDir = git.clone(directWithCreds, suffix);
        git.setAuthor(repoDir, GiteaContainer.VALID_AUTHOR_NAME, GiteaContainer.VALID_AUTHOR_EMAIL);
        git.writeAndStage(repoDir, "file.txt", message + " - " + Instant.now());
        git.commit(repoDir, message);
        // Set push remote to the proxy URL targeting the blocked/denied repo
        git.setRemoteUrl(repoDir, "origin", pushProxyUrl);
        return git.pushWithResult(repoDir);
    }

    // ── Allow rules ──────────────────────────────────────────────────────────

    @Test
    @Order(10)
    void proxy_allowedSlug_passes() throws Exception {
        assertTrue(
                proxyPush(
                        "proxy-allow-slug",
                        GiteaContainer.TEST_ORG,
                        GiteaContainer.TEST_REPO,
                        "feat: allowed by slug rule"),
                "push to /test-owner/test-repo should be allowed");
    }

    @Test
    @Order(11)
    void sf_allowedSlug_passes() throws Exception {
        assertTrue(
                sfPush(
                        "sf-allow-slug",
                        GiteaContainer.TEST_ORG,
                        GiteaContainer.TEST_REPO,
                        "feat: allowed by slug rule in S&F"),
                "push to /test-owner/test-repo should be allowed in S&F mode");
    }

    @Test
    @Order(12)
    void proxy_allowedOwnerGlob_passes() throws Exception {
        assertTrue(
                proxyPush("proxy-allow-glob", "otherorg", "allowed-repo", "feat: allowed by owner glob"),
                "push to otherorg/allowed-repo should be allowed by otherorg/* rule");
    }

    @Test
    @Order(13)
    void sf_allowedOwnerGlob_passes() throws Exception {
        assertTrue(
                sfPush("sf-allow-glob", "otherorg", "allowed-repo", "feat: allowed by owner glob in S&F"),
                "push to otherorg/allowed-repo should be allowed in S&F mode");
    }

    // ── Not in allow list ────────────────────────────────────────────────────

    @Test
    @Order(20)
    void proxy_repoNotInAllowList_blocked() throws Exception {
        // Clone from the allowed test repo, then redirect the push remote to an unconfigured org.
        // The proxy URL rule blocks before any upstream contact.
        var result = cloneFromGiteaThenPushViaProxy(
                GiteaContainer.TEST_ORG,
                GiteaContainer.TEST_REPO,
                proxyUrl("unknown-org", "some-repo"),
                "proxy-notallowed",
                "feat: this org has no allow rule");
        assertFalse(result.succeeded(), "push to unconfigured org/repo should be blocked");
    }

    @Test
    @Order(21)
    void sf_repoNotInAllowList_blocked() throws Exception {
        var result = cloneFromGiteaThenPushViaProxy(
                GiteaContainer.TEST_ORG,
                GiteaContainer.TEST_REPO,
                pushUrl("unknown-org", "some-repo"),
                "sf-notallowed",
                "feat: this org has no allow rule");
        assertFalse(result.succeeded(), "S&F push to unconfigured org/repo should be blocked");
    }

    // ── Deny overrides allow ──────────────────────────────────────────────────

    @Test
    @Order(30)
    void proxy_denySlug_overridesOwnerAllowRule() throws Exception {
        // otherorg/* is allowed but /otherorg/other-secret is explicitly denied.
        // Clone from the allowed allowed-repo, then push to other-secret via the proxy.
        var result = cloneFromGiteaThenPushViaProxy(
                "otherorg",
                "allowed-repo",
                proxyUrl("otherorg", "other-secret"),
                "proxy-deny-slug",
                "feat: this repo is explicitly denied");
        assertFalse(
                result.succeeded(),
                "push to /otherorg/other-secret should be denied even though otherorg/* is allowed");
    }

    @Test
    @Order(31)
    void sf_denySlug_overridesOwnerAllowRule() throws Exception {
        var result = cloneFromGiteaThenPushViaProxy(
                "otherorg",
                "allowed-repo",
                pushUrl("otherorg", "other-secret"),
                "sf-deny-slug",
                "feat: this repo is explicitly denied");
        assertFalse(result.succeeded(), "S&F push to /otherorg/other-secret should be denied");
    }

    // ── Deny by name glob (*-readonly, push only) ─────────────────────────────

    @Test
    @Order(40)
    void proxy_denyNameGlob_pushBlocked() throws Exception {
        // The *-readonly deny is PUSH-only — clone from the same repo (fetch is allowed), then push via proxy.
        var result = cloneFromGiteaThenPushViaProxy(
                GiteaContainer.TEST_ORG,
                "test-repo-readonly",
                proxyUrl(GiteaContainer.TEST_ORG, "test-repo-readonly"),
                "proxy-deny-glob",
                "feat: readonly repos should not accept pushes");
        assertFalse(result.succeeded(), "push to *-readonly repo should be blocked by name glob deny rule");
    }

    @Test
    @Order(41)
    void sf_denyNameGlob_pushBlocked() throws Exception {
        var result = cloneFromGiteaThenPushViaProxy(
                GiteaContainer.TEST_ORG,
                "test-repo-readonly",
                pushUrl(GiteaContainer.TEST_ORG, "test-repo-readonly"),
                "sf-deny-glob",
                "feat: readonly repos should not accept pushes");
        assertFalse(result.succeeded(), "S&F push to *-readonly repo should be blocked");
    }

    // ── Deny by name regex (push only) ───────────────────────────────────────

    @Test
    @Order(50)
    void proxy_denyNameRegex_pushBlocked() throws Exception {
        var result = cloneFromGiteaThenPushViaProxy(
                GiteaContainer.TEST_ORG,
                "secret-store",
                proxyUrl(GiteaContainer.TEST_ORG, "secret-store"),
                "proxy-deny-regex",
                "feat: secret repos should not accept pushes");
        assertFalse(result.succeeded(), "push to secret-store should be blocked by regex deny rule");
    }

    @Test
    @Order(51)
    void sf_denyNameRegex_pushBlocked() throws Exception {
        var result = cloneFromGiteaThenPushViaProxy(
                GiteaContainer.TEST_ORG,
                "secret-store",
                pushUrl(GiteaContainer.TEST_ORG, "secret-store"),
                "sf-deny-regex",
                "feat: secret repos should not accept pushes");
        assertFalse(result.succeeded(), "S&F push to secret-store should be blocked by regex deny rule");
    }

    // ── Rule set — mirrors docker/git-proxy-docker-default.yml ───────────────

    /**
     * Builds the URL rule set used by this test class. The provider reference is needed so that each
     * {@link UrlRuleFilter} is correctly scoped to the test Gitea provider.
     *
     * <p>Rule structure (matches the docker-default config):
     *
     * <ol>
     *   <li>DENY order=100: slug {@code /otherorg/other-secret} — BOTH
     *   <li>DENY order=101: name glob {@code *-readonly} — PUSH only
     *   <li>DENY order=102: name regex {@code (?i)(^|-)secret(-|$).*} — PUSH only
     *   <li>ALLOW order=110: slugs {@code /test-owner/test-repo} — BOTH
     *   <li>ALLOW order=111: owner {@code otherorg} — BOTH
     * </ol>
     */
    private static List<UrlRuleFilter> buildRules(java.net.URI giteaUri) {
        // We need a provider instance for UrlRuleFilter construction.
        var provider = org.finos.gitproxy.provider.GenericProxyProvider.builder()
                .name("gitea-e2e")
                .uri(giteaUri)
                .basePath("")
                .build();

        var bothOps = Set.of(HttpOperation.PUSH, HttpOperation.FETCH);
        var pushOnly = Set.of(HttpOperation.PUSH);

        return List.of(
                // Deny rules (evaluated before allow rules, lower order = higher priority)
                new UrlRuleFilter(
                        100,
                        bothOps,
                        provider,
                        List.of("/otherorg/other-secret"),
                        UrlRuleFilter.Target.SLUG,
                        AccessRule.Access.DENY),
                new UrlRuleFilter(
                        101,
                        pushOnly,
                        provider,
                        List.of("*-readonly"),
                        UrlRuleFilter.Target.NAME,
                        AccessRule.Access.DENY),
                new UrlRuleFilter(
                        102,
                        pushOnly,
                        provider,
                        List.of("regex:(?i)(^|-)secret(-|$).*"),
                        UrlRuleFilter.Target.NAME,
                        AccessRule.Access.DENY),

                // Allow rules
                new UrlRuleFilter(
                        110,
                        bothOps,
                        provider,
                        List.of("/test-owner/test-repo"),
                        UrlRuleFilter.Target.SLUG,
                        AccessRule.Access.ALLOW),
                new UrlRuleFilter(
                        111,
                        bothOps,
                        provider,
                        List.of("otherorg"),
                        UrlRuleFilter.Target.OWNER,
                        AccessRule.Access.ALLOW));
    }
}
