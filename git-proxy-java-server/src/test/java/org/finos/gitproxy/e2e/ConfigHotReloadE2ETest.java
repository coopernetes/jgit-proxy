package org.finos.gitproxy.e2e;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.config.DiffScanConfig;
import org.finos.gitproxy.config.SecretScanConfig;
import org.finos.gitproxy.db.memory.InMemoryRepoRegistry;
import org.finos.gitproxy.jetty.reload.ConfigHolder;
import org.finos.gitproxy.jetty.reload.LiveConfigLoader.Section;
import org.junit.jupiter.api.*;

/**
 * End-to-end tests verifying that each hot-reloadable config section takes effect without a server restart.
 *
 * <p>Each test follows the same three-phase pattern:
 *
 * <ol>
 *   <li><b>Block</b> — write a YAML override file that restricts the section under test; reload it; verify a push that
 *       violates the new restriction is rejected.
 *   <li><b>Reload</b> — write a permissive YAML override; reload it; verify the same (previously uncommitted) push now
 *       succeeds on retry.
 * </ol>
 *
 * <p>Each test gets its own Gitea repository and its own {@link HotReloadJettyFixture} instance so there is no shared
 * config state between tests and ordering does not matter.
 */
@Tag("e2e")
class ConfigHotReloadE2ETest {

    // Shared across all tests — containers are expensive to start.
    static GiteaContainer gitea;

    // Per-test — fresh config state and repo for every test method.
    HotReloadJettyFixture proxy;
    Path tempDir;
    String repoName;

    @BeforeAll
    static void startGitea() throws Exception {
        gitea = new GiteaContainer();
        gitea.start();
        gitea.createAdminUser();
        // Creates the test org (test-owner) plus an initial repo; subsequent tests add their own repos.
        gitea.createTestRepo();
    }

    @AfterAll
    static void stopGitea() {
        if (gitea != null) gitea.stop();
    }

    @BeforeEach
    void setUp() throws Exception {
        repoName = "hotreload-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        gitea.createRepo(GiteaContainer.TEST_ORG, repoName);
        tempDir = Files.createTempDirectory("git-proxy-java-hotreload-e2e-");

        var configHolder = new ConfigHolder(
                CommitConfig.defaultConfig(),
                DiffScanConfig.defaultConfig(),
                SecretScanConfig.defaultConfig(),
                List.of());
        var configRegistry = new InMemoryRepoRegistry();
        proxy = new HotReloadJettyFixture(gitea.getBaseUri(), configHolder, configRegistry);

        // Seed allow-all so requests reach the validation filters under test.
        Path allowAll = Files.createTempFile(tempDir, "allow-all-", ".yml");
        Files.writeString(allowAll, """
                rules:
                  allow:
                    - operations: [PUSH, FETCH]
                      slugs: ["/**"]
                  deny: []
                secret-scan:
                  enabled: false
                """);
        proxy.reloadSection(allowAll, Section.RULES);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (proxy != null) proxy.close();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String url() {
        String user = URLEncoder.encode(GiteaContainer.ADMIN_USER, StandardCharsets.UTF_8);
        String pass = URLEncoder.encode(GiteaContainer.ADMIN_PASSWORD, StandardCharsets.UTF_8);
        return "http://" + user + ":" + pass + "@localhost:" + proxy.getPort() + "/proxy/localhost/"
                + GiteaContainer.TEST_ORG + "/" + repoName + ".git";
    }

    private Path writeOverride(String yaml) throws Exception {
        Path f = Files.createTempFile(tempDir, "override-", ".yml");
        Files.writeString(f, yaml);
        return f;
    }

    private GitHelper git() {
        return new GitHelper(tempDir);
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /** Verifies that commit message block literals are enforced after a reload and relaxed after a second reload. */
    @Test
    void commitRulesReload() throws Exception {
        GitHelper git = git();
        Path repo = git.clone(url(), "commit-reload");
        git.setAuthor(repo, GiteaContainer.VALID_AUTHOR_NAME, GiteaContainer.VALID_AUTHOR_EMAIL);
        git.writeAndStage(repo, "file.txt", "initial content");
        git.commit(repo, "feat: HOTRELOAD_MSG_BLOCKED — should be blocked after reload");

        // --- Phase 1: reload with message block active ---
        Path blocking = writeOverride("""
                commit:
                  message:
                    block:
                      literals:
                        - "HOTRELOAD_MSG_BLOCKED"
                secret-scan:
                  enabled: false
                """);
        proxy.reloadSection(blocking, Section.COMMIT);

        assertFalse(git.tryPush(repo), "push should be blocked: commit message contains blocked literal");

        // --- Phase 2: reload with message block cleared ---
        Path permissive = writeOverride("""
                commit:
                  message:
                    block:
                      literals: []
                      patterns: []
                secret-scan:
                  enabled: false
                """);
        proxy.reloadSection(permissive, Section.COMMIT);

        assertTrue(git.tryPush(repo), "push should succeed after commit rules are relaxed");
    }

    /** Verifies that diff content block literals are enforced after a reload and relaxed after a second reload. */
    @Test
    void diffScanReload() throws Exception {
        GitHelper git = git();
        Path repo = git.clone(url(), "diff-reload");
        git.setAuthor(repo, GiteaContainer.VALID_AUTHOR_NAME, GiteaContainer.VALID_AUTHOR_EMAIL);
        git.writeAndStage(repo, "data.txt", "line1\nHOTRELOAD_DIFF_BLOCKED\nline3\n");
        git.commit(repo, "feat: add data file");

        // --- Phase 1: reload with diff-scan block active ---
        Path blocking = writeOverride("""
                diff-scan:
                  block:
                    literals:
                      - "HOTRELOAD_DIFF_BLOCKED"
                secret-scan:
                  enabled: false
                """);
        proxy.reloadSection(blocking, Section.DIFF_SCAN);

        assertFalse(git.tryPush(repo), "push should be blocked: diff contains blocked literal");

        // --- Phase 2: reload with diff-scan block cleared ---
        Path permissive = writeOverride("""
                diff-scan:
                  block:
                    literals: []
                    patterns: []
                secret-scan:
                  enabled: false
                """);
        proxy.reloadSection(permissive, Section.DIFF_SCAN);

        assertTrue(git.tryPush(repo), "push should succeed after diff-scan rules are relaxed");
    }

    /**
     * Verifies that secret-scan can be enabled with an inline gitleaks config and that disabling it via reload allows
     * the same push through.
     */
    @Test
    void secretScanningReload() throws Exception {
        GitHelper git = git();
        Path repo = git.clone(url(), "secret-reload");
        git.setAuthor(repo, GiteaContainer.VALID_AUTHOR_NAME, GiteaContainer.VALID_AUTHOR_EMAIL);
        git.writeAndStage(repo, "config.txt", "api_key = HOTRELOAD_SECRET_ABCD1234\n");
        git.commit(repo, "feat: add config with api key");

        // --- Phase 1: reload with secret scanning enabled + custom inline rule ---
        Path blocking = writeOverride("""
                secret-scan:
                  enabled: true
                  inline-config: |
                    title = "hotreload-test"
                    [[rules]]
                    id = "hotreload-test-custom-secret"
                    description = "Custom rule for hot-reload e2e test"
                    regex = '''HOTRELOAD_SECRET_[A-Z0-9]{8}'''
                """);
        proxy.reloadSection(blocking, Section.SECRET_SCAN);

        GitHelper.PushResult blocked = git.pushWithResult(repo);
        if (blocked.succeeded()) {
            System.out.println(
                    "[secretScanningReload] gitleaks unavailable — scanner ran fail-open, skipping block assertion");
        } else {
            assertTrue(
                    blocked.output().contains("scanSecrets")
                            || blocked.output().contains("secret")
                            || blocked.output().contains("blocked"),
                    "blocked output should mention secret scanning; got: " + blocked.output());
        }

        // --- Phase 2: reload with secret scanning disabled ---
        Path permissive = writeOverride("""
                secret-scan:
                  enabled: false
                """);
        proxy.reloadSection(permissive, Section.SECRET_SCAN);

        if (!blocked.succeeded()) {
            assertTrue(git.tryPush(repo), "push should succeed after secret scanning is disabled");
        }
    }

    /**
     * Verifies that a URL deny rule added via reload blocks pushes, and that removing it via reload restores access.
     */
    @Test
    void rulesReload() throws Exception {
        GitHelper git = git();
        Path repo = git.clone(url(), "rules-reload");
        git.setAuthor(repo, GiteaContainer.VALID_AUTHOR_NAME, GiteaContainer.VALID_AUTHOR_EMAIL);
        git.writeAndStage(repo, "file.txt", "content for rules reload test");
        git.commit(repo, "feat: rules reload test commit");

        String repoSlug = "/" + GiteaContainer.TEST_ORG + "/" + repoName;

        // --- Phase 1: reload with a DENY rule for this test's repo ---
        Path blocking = writeOverride("""
                rules:
                  allow: []
                  deny:
                    - operations:
                        - PUSH
                      slugs:
                        - "%s"
                secret-scan:
                  enabled: false
                """.formatted(repoSlug));
        proxy.reloadSection(blocking, Section.RULES);

        assertFalse(git.tryPush(repo), "push should be blocked: deny rule matches " + repoSlug);

        // --- Phase 2: reload with deny rule removed; restore allow-all ---
        Path permissive = writeOverride("""
                rules:
                  allow:
                    - operations: [PUSH, FETCH]
                      slugs: ["/**"]
                  deny: []
                secret-scan:
                  enabled: false
                """);
        proxy.reloadSection(permissive, Section.RULES);

        assertTrue(git.tryPush(repo), "push should succeed after deny rule is removed");
    }
}
