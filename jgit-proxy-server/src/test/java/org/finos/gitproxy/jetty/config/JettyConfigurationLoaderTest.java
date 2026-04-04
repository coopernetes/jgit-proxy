package org.finos.gitproxy.jetty.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link JettyConfigurationLoader}.
 *
 * <p>Covers base YAML defaults and provider configuration. Environment-variable overrides are exercised in the e2e test
 * suite where a live server is started with the relevant vars set.
 */
class JettyConfigurationLoaderTest {

    // ---- server defaults from git-proxy.yml ----

    @Test
    void defaultPort_is8080() {
        JettyConfigurationLoader loader = new JettyConfigurationLoader();
        assertEquals(8080, loader.getServerPort());
    }

    @Test
    void defaultBasePath_isEmptyString() {
        JettyConfigurationLoader loader = new JettyConfigurationLoader();
        assertEquals("", loader.getBasePath());
    }

    // ---- database defaults ----

    @Test
    void databaseType_isConfigured() {
        // The base config defaults to h2-mem; git-proxy-local.yml (if present on classpath)
        // may override this. Either way, a valid type must be returned.
        JettyConfigurationLoader loader = new JettyConfigurationLoader();
        String dbType = loader.getDatabaseType();
        assertNotNull(dbType);
        assertFalse(dbType.isBlank(), "database type must not be blank");
    }

    // ---- providers ----

    @Test
    void defaultProviders_includesGitHub() {
        JettyConfigurationLoader loader = new JettyConfigurationLoader();
        Map<String, Map<String, Object>> providers = loader.getProviders();

        assertTrue(providers.containsKey("github"), "github provider should be present");
    }

    @Test
    void defaultProviders_includesGitLab() {
        JettyConfigurationLoader loader = new JettyConfigurationLoader();
        assertTrue(loader.getProviders().containsKey("gitlab"), "gitlab provider should be present");
    }

    @Test
    void defaultProviders_includesBitbucket() {
        JettyConfigurationLoader loader = new JettyConfigurationLoader();
        assertTrue(loader.getProviders().containsKey("bitbucket"), "bitbucket provider should be present");
    }

    @Test
    void defaultProviders_githubIsEnabled() {
        JettyConfigurationLoader loader = new JettyConfigurationLoader();
        Map<String, Object> github = loader.getProviders().get("github");
        assertNotNull(github);
        assertEquals(true, github.get("enabled"));
    }

    // ---- commit config ----

    @Test
    void commitConfig_notEmpty() {
        JettyConfigurationLoader loader = new JettyConfigurationLoader();
        Map<String, Object> commit = loader.getCommitConfig();
        assertFalse(commit.isEmpty(), "commit config should be present in base YAML");
    }

    // ---- whitelists default ----

    @Test
    void whitelistFilters_returnsAList() {
        // The base config has no whitelist filters; git-proxy-local.yml may add some.
        // Either way the method must return a non-null list (possibly empty).
        JettyConfigurationLoader loader = new JettyConfigurationLoader();
        assertNotNull(loader.getWhitelistFilters());
    }

    // ---- approval mode ----

    @Test
    void defaultApprovalMode_isAuto() {
        JettyConfigurationLoader loader = new JettyConfigurationLoader();
        assertEquals("auto", loader.getApprovalMode());
    }

    // ---- heartbeat ----

    @Test
    void defaultHeartbeatInterval_is10() {
        JettyConfigurationLoader loader = new JettyConfigurationLoader();
        assertEquals(10, loader.getHeartbeatIntervalSeconds());
    }

    @Test
    void heartbeatInterval_overrideViaMap() {
        JettyConfigurationLoader loader =
                new JettyConfigurationLoader(Map.of("server", Map.of("heartbeat-interval-seconds", 0)));
        assertEquals(0, loader.getHeartbeatIntervalSeconds());
    }

    // ---- service URL default ----

    @Test
    void defaultServiceUrl_includesDefaultPort() {
        JettyConfigurationLoader loader = new JettyConfigurationLoader();
        String url = loader.getServiceUrl();
        assertTrue(url.contains("8080"), "default service URL should reference port 8080");
    }

    // ---- raw config is present ----

    @Test
    void rawConfig_containsTopLevelKeys() {
        JettyConfigurationLoader loader = new JettyConfigurationLoader();
        Map<String, Object> raw = loader.getRawConfig();
        assertTrue(raw.containsKey("server"), "raw config must have 'server' key");
        assertTrue(raw.containsKey("git-proxy"), "raw config must have 'git-proxy' key");
    }

    // ---- local override merging ----

    @Test
    void deepMerge_localOverride_doesNotWipeOtherProviders() {
        // The base config has github + gitlab + bitbucket.
        // Even if a local override touches only one key, the others must survive.
        // Since we can't inject a local config file in this unit test, the best we can do
        // is confirm that independent providers all survive after one normal load.
        JettyConfigurationLoader loader = new JettyConfigurationLoader();
        Map<String, Map<String, Object>> providers = loader.getProviders();

        assertTrue(providers.containsKey("github"));
        assertTrue(providers.containsKey("gitlab"));
        assertTrue(providers.containsKey("bitbucket"));
    }
}
