package org.finos.gitproxy.jetty.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.github.gestalt.config.exceptions.GestaltException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link GitProxyConfigLoader}.
 *
 * <p>Verifies that the base config ({@code git-proxy.yml}) is loaded and merged with local overrides
 * ({@code git-proxy-local.yml}) correctly. Environment variable overrides are exercised in the e2e suite.
 */
class GitProxyConfigLoaderTest {

    // --- server defaults ---

    @Test
    void defaultPort_is8080() throws GestaltException {
        GitProxyConfig config = GitProxyConfigLoader.load();
        assertEquals(8080, config.getServer().getPort());
    }

    @Test
    void defaultApprovalMode_isAuto() throws GestaltException {
        GitProxyConfig config = GitProxyConfigLoader.load();
        assertEquals("auto", config.getServer().getApprovalMode());
    }

    @Test
    void defaultHeartbeatInterval_is10() throws GestaltException {
        GitProxyConfig config = GitProxyConfigLoader.load();
        assertEquals(10, config.getServer().getHeartbeatIntervalSeconds());
    }

    // --- database defaults ---

    @Test
    void databaseType_isConfigured() throws GestaltException {
        String type = GitProxyConfigLoader.load().getDatabase().getType();
        assertNotNull(type);
        assertFalse(type.isBlank());
    }

    // --- providers ---

    @Test
    void defaultProviders_includesGitHub() throws GestaltException {
        assertTrue(GitProxyConfigLoader.load().getProviders().containsKey("github"));
    }

    @Test
    void defaultProviders_includesGitLab() throws GestaltException {
        assertTrue(GitProxyConfigLoader.load().getProviders().containsKey("gitlab"));
    }

    @Test
    void defaultProviders_includesCodeberg() throws GestaltException {
        assertTrue(GitProxyConfigLoader.load().getProviders().containsKey("codeberg"));
    }

    @Test
    void defaultProviders_includesGitea() throws GestaltException {
        assertTrue(GitProxyConfigLoader.load().getProviders().containsKey("gitea"));
    }

    @Test
    void defaultProviders_githubIsEnabled() throws GestaltException {
        ProviderConfig github = GitProxyConfigLoader.load().getProviders().get("github");
        assertNotNull(github);
        assertTrue(github.isEnabled());
    }

    @Test
    void deepMerge_localOverride_doesNotWipeOtherProviders() throws GestaltException {
        // Even if a local override touches only one key, all three built-in providers must survive.
        var providers = GitProxyConfigLoader.load().getProviders();
        assertTrue(providers.containsKey("github"));
        assertTrue(providers.containsKey("gitlab"));
        assertTrue(providers.containsKey("codeberg"));
        assertTrue(providers.containsKey("gitea"));
    }

    // --- commit config presence ---

    @Test
    void commitConfig_secretScanning_hasDefault() throws GestaltException {
        var ss = GitProxyConfigLoader.load().getSecretScan();
        assertNotNull(ss);
        // base config ships with secret scanning enabled
        assertTrue(ss.isEnabled());
    }

    // --- rules ---

    @Test
    void rules_allowList_returnsNonNull() throws GestaltException {
        assertNotNull(GitProxyConfigLoader.load().getRules().getAllow());
    }

    // --- loadWithOverride (externalized config path) ---
    //
    // These tests exercise the same code path used by:
    //   • LiveConfigLoader (hot-reload from a watched file or a git-sourced file)
    //   • The Docker /app/conf/ pattern when GITPROXY_CONFIG_PROFILES is not set and an
    //     explicit reload is triggered via POST /api/config/reload
    //
    // When a test here fails, the cause is almost always a renamed config key. Check
    // whether the YAML key in the override file still matches the field name in the
    // corresponding Settings POJO (e.g. SecretScanSettings, DiffScanSettings, CommitSettings).

    @TempDir
    Path tempDir;

    @Test
    void loadWithOverride_secretScan_disabledOverridesDefault() throws GestaltException, IOException {
        // Base config ships with secret-scan.enabled: true — override to false.
        Path override = writeYaml("""
                secret-scan:
                  enabled: false
                """);
        var config = GitProxyConfigLoader.loadWithOverride(override);
        assertFalse(
                config.getSecretScan().isEnabled(),
                "override file secret-scan.enabled: false should win over base default");
    }

    @Test
    void loadWithOverride_diffScan_blockLiterals() throws GestaltException, IOException {
        Path override = writeYaml("""
                diff-scan:
                  block:
                    literals:
                      - "CANARY_STRING"
                """);
        var config = GitProxyConfigLoader.loadWithOverride(override);
        assertTrue(
                config.getDiffScan().getBlock().getLiterals().contains("CANARY_STRING"),
                "override file diff-scan.block.literals should be present");
    }

    @Test
    void loadWithOverride_commit_messageBlockLiteral() throws GestaltException, IOException {
        Path override = writeYaml("""
                commit:
                  message:
                    block:
                      literals:
                        - "DO_NOT_PUSH"
                """);
        var config = GitProxyConfigLoader.loadWithOverride(override);
        assertTrue(
                config.getCommit().getMessage().getBlock().getLiterals().contains("DO_NOT_PUSH"),
                "override file commit.message.block.literals should be present");
    }

    @Test
    void loadWithOverride_baseValuesPreservedWhenNotOverridden() throws GestaltException, IOException {
        // An override that only touches one key must not wipe out other base defaults.
        Path override = writeYaml("""
                server:
                  port: 9999
                """);
        var config = GitProxyConfigLoader.loadWithOverride(override);
        assertEquals(9999, config.getServer().getPort());
        // Base providers must survive the merge
        assertTrue(config.getProviders().containsKey("github"), "base providers must survive a partial override");
        // Base secret-scan default must survive
        assertTrue(
                config.getSecretScan().isEnabled(), "base secret-scan.enabled: true must survive a partial override");
    }

    @Test
    void loadWithOverride_friendlyProviderName_inPermissions() throws GestaltException, IOException {
        // Verifies that friendly provider names in an externalized config are accepted.
        // This is the key regression test for #127 — if the key rename broke parsing,
        // provider references in ConfigMaps would silently fail validation at startup.
        Path override = writeYaml("""
                permissions:
                  - username: test-user
                    provider: github
                    path: /org/repo
                    operations: PUSH
                """);
        var config = GitProxyConfigLoader.loadWithOverride(override);
        assertFalse(config.getPermissions().isEmpty(), "permissions from override should be loaded");
        assertEquals(
                "github",
                config.getPermissions().get(0).getProvider(),
                "friendly provider name should round-trip through the parser");
    }

    @Test
    void loadWithOverride_attestations_partialOverride_preservesBaseDefaults() throws GestaltException, IOException {
        // Attestations are a top-level list, so a partial override that only ships `attestations:`
        // replaces just that list without touching providers/commit/diff-scan/etc. This is the
        // shape operators should use to hot-reload review prompts.
        Path override = writeYaml("""
                attestations:
                  - id: override-only
                    type: checkbox
                    label: "I attest this change is authorized"
                    required: true
                """);
        var config = GitProxyConfigLoader.loadWithOverride(override);

        assertEquals(1, config.getAttestations().size());
        assertEquals("override-only", config.getAttestations().get(0).getId());
        // Base providers / secret-scan defaults must survive untouched.
        assertTrue(config.getProviders().containsKey("github"));
        assertTrue(config.getProviders().get("github").isEnabled());
        assertTrue(config.getSecretScan().isEnabled());
    }

    private Path writeYaml(String yaml) throws IOException {
        Path f = Files.createTempFile(tempDir, "override-", ".yml");
        Files.writeString(f, yaml);
        return f;
    }
}
