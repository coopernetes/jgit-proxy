package org.finos.gitproxy.jetty.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.finos.gitproxy.approval.AutoApprovalGateway;
import org.finos.gitproxy.approval.UiApprovalGateway;
import org.finos.gitproxy.db.PushStoreFactory;
import org.finos.gitproxy.db.model.AccessRule;
import org.finos.gitproxy.permission.RepoPermission;
import org.finos.gitproxy.provider.GitHubProvider;
import org.junit.jupiter.api.Test;

class JettyConfigurationBuilderTest {

    // ---- buildApprovalGateway ----

    @Test
    void buildApprovalGateway_defaultConfig_returnsAutoApprovalGateway() {
        var builder = new JettyConfigurationBuilder(configWithApprovalMode("auto"));
        var gateway = builder.buildApprovalGateway(PushStoreFactory.inMemory());
        assertInstanceOf(AutoApprovalGateway.class, gateway);
    }

    @Test
    void buildApprovalGateway_uiMode_returnsUiApprovalGateway() {
        var builder = new JettyConfigurationBuilder(configWithApprovalMode("ui"));
        var gateway = builder.buildApprovalGateway(PushStoreFactory.inMemory());
        assertInstanceOf(UiApprovalGateway.class, gateway);
    }

    @Test
    void buildApprovalGateway_unknownMode_fallsBackToAuto() {
        var builder = new JettyConfigurationBuilder(configWithApprovalMode("bogus"));
        var gateway = builder.buildApprovalGateway(PushStoreFactory.inMemory());
        assertInstanceOf(AutoApprovalGateway.class, gateway);
    }

    // ---- validateProviderReferences ----

    @Test
    void validateProviderReferences_validConfig_doesNotThrow() {
        var config = configWithGithub();
        var permission = new PermissionConfig();
        permission.setUsername("alice");
        permission.setProvider("github");
        permission.setPath("/org/repo");
        config.setPermissions(List.of(permission));

        assertDoesNotThrow(() -> new JettyConfigurationBuilder(config).validateProviderReferences());
    }

    @Test
    void validateProviderReferences_unknownPermissionProvider_throws() {
        var config = configWithGithub();
        var permission = new PermissionConfig();
        permission.setUsername("alice");
        permission.setProvider("not-a-provider");
        permission.setPath("/org/repo");
        config.setPermissions(List.of(permission));

        var builder = new JettyConfigurationBuilder(config);
        var ex = assertThrows(IllegalStateException.class, builder::validateProviderReferences);
        assertTrue(ex.getMessage().contains("not-a-provider"));
        // Should list the configured providers in the error
        assertTrue(ex.getMessage().contains("github"));
    }

    @Test
    void validateProviderReferences_unknownRuleProvider_throws() {
        var config = configWithGithub();
        var ruleConfig = new RuleConfig();
        ruleConfig.setProviders(List.of("typo-provider"));
        ruleConfig.setSlugs(List.of("/org/repo"));
        config.getRules().setAllow(List.of(ruleConfig));

        var builder = new JettyConfigurationBuilder(config);
        assertThrows(IllegalStateException.class, builder::validateProviderReferences);
    }

    @Test
    void validateProviderReferences_emptyConfig_doesNotThrow() {
        // No providers, no cross-references → nothing to validate
        assertDoesNotThrow(() -> new JettyConfigurationBuilder(new GitProxyConfig()).validateProviderReferences());
    }

    // ---- buildProviderRegistry (#127) ----

    @Test
    void buildProviderRegistry_keyedByFriendlyName() {
        var builder = new JettyConfigurationBuilder(configWithGithub());
        var registry = builder.buildProviderRegistry();

        // Lookup by friendly name
        assertNotNull(registry.getProvider("github"));
        assertInstanceOf(GitHubProvider.class, registry.getProvider("github"));
        // Lookup by type/host ID via resolveProvider
        assertNotNull(registry.resolveProvider("github/github.com"));
        assertSame(registry.getProvider("github"), registry.resolveProvider("github/github.com"));
        // Friendly name and ID resolve to same provider
        assertEquals(
                registry.resolveProvider("github").getProviderId(),
                registry.resolveProvider("github/github.com").getProviderId());
    }

    // ---- buildConfigPermissions — friendly name resolution (#127) ----

    @Test
    void buildConfigPermissions_friendlyName_resolvesToCanonicalId() {
        var config = configWithGithub();
        var permission = new PermissionConfig();
        permission.setUsername("alice");
        permission.setProvider("github"); // friendly name
        permission.setPath("/org/repo");
        config.setPermissions(List.of(permission));

        List<RepoPermission> perms = new JettyConfigurationBuilder(config).buildConfigPermissions(config);

        assertEquals(1, perms.size());
        // Friendly name must be resolved to the canonical type/host ID
        assertEquals("github/github.com", perms.get(0).getProvider());
        assertEquals("alice", perms.get(0).getUsername());
    }

    @Test
    void buildConfigPermissions_typeHostId_backwardsCompat() {
        var config = configWithGithub();
        var permission = new PermissionConfig();
        permission.setUsername("bob");
        permission.setProvider("github/github.com"); // legacy type/host form
        permission.setPath("/org/repo");
        config.setPermissions(List.of(permission));

        List<RepoPermission> perms = new JettyConfigurationBuilder(config).buildConfigPermissions(config);

        assertEquals(1, perms.size());
        assertEquals("github/github.com", perms.get(0).getProvider());
    }

    @Test
    void buildConfigPermissions_unknownProvider_throwsWithHelpfulMessage() {
        var config = configWithGithub();
        var permission = new PermissionConfig();
        permission.setUsername("carol");
        permission.setProvider("nonexistent");
        permission.setPath("/org/repo");
        config.setPermissions(List.of(permission));

        var builder = new JettyConfigurationBuilder(config);
        var ex = assertThrows(IllegalStateException.class, () -> builder.buildConfigPermissions(config));
        assertTrue(ex.getMessage().contains("nonexistent"), "error should name the unknown provider");
        assertTrue(
                ex.getMessage().contains("github") || ex.getMessage().contains("github/github.com"),
                "error should list configured providers");
    }

    // ---- buildConfigRules — friendly name resolution (#127) ----

    @Test
    void buildConfigRules_friendlyName_resolvesToCanonicalId() {
        var config = configWithGithub();
        var ruleConfig = new RuleConfig();
        ruleConfig.setProviders(List.of("github")); // friendly name
        ruleConfig.setSlugs(List.of("/org/repo"));
        config.getRules().setAllow(List.of(ruleConfig));

        List<AccessRule> rules = new JettyConfigurationBuilder(config).buildConfigRules(config);

        assertFalse(rules.isEmpty());
        // Provider field in AccessRule must be the canonical type/host ID for evaluator to work
        assertEquals("github/github.com", rules.get(0).getProvider());
    }

    @Test
    void buildConfigRules_noProviderFilter_storesNullProvider() {
        var config = configWithGithub();
        var ruleConfig = new RuleConfig();
        // no providers → applies to all
        ruleConfig.setSlugs(List.of("/org/repo"));
        config.getRules().setAllow(List.of(ruleConfig));

        List<AccessRule> rules = new JettyConfigurationBuilder(config).buildConfigRules(config);

        assertFalse(rules.isEmpty());
        assertNull(rules.get(0).getProvider()); // null = all providers
    }

    // ---- buildUrlRuleFilters — friendly name scoping (#127) ----

    @Test
    void buildUrlRuleFilters_friendlyName_includesRuleForMatchingProvider() {
        var config = configWithGithub();
        var ruleConfig = new RuleConfig();
        ruleConfig.setOrder(110); // must be 50-199 for UrlRuleFilter
        ruleConfig.setProviders(List.of("github")); // friendly name
        ruleConfig.setSlugs(List.of("/org/repo"));
        config.getRules().setAllow(List.of(ruleConfig));

        var builder = new JettyConfigurationBuilder(config);
        var githubProvider = builder.buildProviderRegistry().getProvider("github");
        var filters = builder.buildUrlRuleFilters(githubProvider);

        assertFalse(filters.isEmpty(), "rule scoped to 'github' should produce a filter for the GitHub provider");
    }

    @Test
    void buildUrlRuleFilters_friendlyName_excludesRuleForDifferentProvider() {
        var config = configWithGithubAndGitlab();
        var ruleConfig = new RuleConfig();
        ruleConfig.setOrder(110);
        ruleConfig.setProviders(List.of("github")); // only github
        ruleConfig.setSlugs(List.of("/org/repo"));
        config.getRules().setAllow(List.of(ruleConfig));

        var builder = new JettyConfigurationBuilder(config);
        var gitlabProvider = builder.buildProviderRegistry().getProvider("gitlab");
        var filters = builder.buildUrlRuleFilters(gitlabProvider);

        assertTrue(filters.isEmpty(), "rule scoped to 'github' should not produce a filter for the GitLab provider");
    }

    // ---- helpers ----

    private static GitProxyConfig configWithApprovalMode(String mode) {
        var config = new GitProxyConfig();
        config.getServer().setApprovalMode(mode);
        return config;
    }

    private static GitProxyConfig configWithGithub() {
        var config = new GitProxyConfig();
        var providerConfig = new ProviderConfig();
        providerConfig.setEnabled(true);
        config.setProviders(Map.of("github", providerConfig));
        return config;
    }

    private static GitProxyConfig configWithGithubAndGitlab() {
        var config = new GitProxyConfig();
        var github = new ProviderConfig();
        github.setEnabled(true);
        var gitlab = new ProviderConfig();
        gitlab.setEnabled(true);
        config.setProviders(Map.of("github", github, "gitlab", gitlab));
        return config;
    }
}
