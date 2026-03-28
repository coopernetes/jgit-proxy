package org.finos.gitproxy.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.finos.gitproxy.provider.GitHubProvider;
import org.finos.gitproxy.provider.GitLabProvider;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.servlet.filter.GitProxyFilter;
import org.junit.jupiter.api.Test;

class ConfigTest {

    // --- CommitConfig ---

    @Test
    void commitConfig_defaultConfig_hasNoRestrictions() {
        CommitConfig config = CommitConfig.defaultConfig();
        assertNotNull(config);
        assertNotNull(config.getAuthor());
        assertNotNull(config.getAuthor().getEmail());
        assertNotNull(config.getAuthor().getEmail().getDomain());
        assertNotNull(config.getAuthor().getEmail().getLocal());
        assertNull(config.getAuthor().getEmail().getDomain().getAllow());
        assertNull(config.getAuthor().getEmail().getLocal().getBlock());
        assertNotNull(config.getMessage());
        assertNotNull(config.getMessage().getBlock());
        assertTrue(config.getMessage().getBlock().getLiterals().isEmpty());
        assertTrue(config.getMessage().getBlock().getPatterns().isEmpty());
    }

    @Test
    void commitConfig_builder_setsEmailDomainAllow() {
        Pattern domainPattern = Pattern.compile("example\\.com$");
        CommitConfig config = CommitConfig.builder()
                .author(CommitConfig.AuthorConfig.builder()
                        .email(CommitConfig.EmailConfig.builder()
                                .domain(CommitConfig.DomainConfig.builder()
                                        .allow(domainPattern)
                                        .build())
                                .build())
                        .build())
                .build();
        assertSame(domainPattern, config.getAuthor().getEmail().getDomain().getAllow());
    }

    @Test
    void commitConfig_builder_setsEmailLocalBlock() {
        Pattern blockPattern = Pattern.compile("^noreply$");
        CommitConfig config = CommitConfig.builder()
                .author(CommitConfig.AuthorConfig.builder()
                        .email(CommitConfig.EmailConfig.builder()
                                .local(CommitConfig.LocalConfig.builder()
                                        .block(blockPattern)
                                        .build())
                                .build())
                        .build())
                .build();
        assertSame(blockPattern, config.getAuthor().getEmail().getLocal().getBlock());
    }

    @Test
    void commitConfig_builder_setsMessageBlockLiterals() {
        CommitConfig config = CommitConfig.builder()
                .message(CommitConfig.MessageConfig.builder()
                        .block(CommitConfig.BlockConfig.builder()
                                .literals(List.of("WIP", "DO NOT MERGE"))
                                .build())
                        .build())
                .build();
        assertEquals(
                List.of("WIP", "DO NOT MERGE"), config.getMessage().getBlock().getLiterals());
    }

    @Test
    void commitConfig_builder_setsMessageBlockPatterns() {
        Pattern p = Pattern.compile("password\\s*=");
        CommitConfig config = CommitConfig.builder()
                .message(CommitConfig.MessageConfig.builder()
                        .block(CommitConfig.BlockConfig.builder()
                                .patterns(List.of(p))
                                .build())
                        .build())
                .build();
        assertEquals(1, config.getMessage().getBlock().getPatterns().size());
        assertSame(p, config.getMessage().getBlock().getPatterns().get(0));
    }

    // --- GpgConfig ---

    @Test
    void gpgConfig_defaultConfig_isDisabled() {
        GpgConfig config = GpgConfig.defaultConfig();
        assertFalse(config.isEnabled());
        assertFalse(config.isRequireSignedCommits());
        assertNull(config.getTrustedKeysFile());
        assertNull(config.getTrustedKeysInline());
    }

    @Test
    void gpgConfig_builder_setsEnabled() {
        GpgConfig config =
                GpgConfig.builder().enabled(true).requireSignedCommits(true).build();
        assertTrue(config.isEnabled());
        assertTrue(config.isRequireSignedCommits());
    }

    @Test
    void gpgConfig_builder_setsTrustedKeysFile() {
        GpgConfig config =
                GpgConfig.builder().trustedKeysFile("/path/to/keys.asc").build();
        assertEquals("/path/to/keys.asc", config.getTrustedKeysFile());
    }

    @Test
    void gpgConfig_builder_setsTrustedKeysInline() {
        GpgConfig config =
                GpgConfig.builder().trustedKeysInline("-----BEGIN PGP...").build();
        assertEquals("-----BEGIN PGP...", config.getTrustedKeysInline());
    }

    // --- InMemoryFilterConfigurationSource ---

    @Test
    void filterConfig_globalFilters_returnedForAnyProvider() {
        GitProxyFilter globalFilter = mock(GitProxyFilter.class);
        var source = new InMemoryFilterConfigurationSource(Map.of(), List.of(globalFilter));

        List<GitProxyFilter> result = source.getFiltersForProvider("github");
        assertEquals(1, result.size());
        assertSame(globalFilter, result.get(0));
    }

    @Test
    void filterConfig_providerFilters_addedAfterGlobal() {
        GitProxyFilter globalFilter = mock(GitProxyFilter.class);
        GitProxyFilter providerFilter = mock(GitProxyFilter.class);
        var source =
                new InMemoryFilterConfigurationSource(Map.of("github", List.of(providerFilter)), List.of(globalFilter));

        List<GitProxyFilter> result = source.getFiltersForProvider("github");
        assertEquals(2, result.size());
        assertSame(globalFilter, result.get(0));
        assertSame(providerFilter, result.get(1));
    }

    @Test
    void filterConfig_unknownProvider_returnsOnlyGlobal() {
        GitProxyFilter globalFilter = mock(GitProxyFilter.class);
        GitProxyFilter providerFilter = mock(GitProxyFilter.class);
        var source =
                new InMemoryFilterConfigurationSource(Map.of("github", List.of(providerFilter)), List.of(globalFilter));

        List<GitProxyFilter> result = source.getFiltersForProvider("gitlab");
        assertEquals(1, result.size());
        assertSame(globalFilter, result.get(0));
    }

    @Test
    void filterConfig_getAllFilters_returnsGlobalAndProviderFilters() {
        GitProxyFilter globalFilter = mock(GitProxyFilter.class);
        GitProxyFilter providerFilter = mock(GitProxyFilter.class);
        var source =
                new InMemoryFilterConfigurationSource(Map.of("github", List.of(providerFilter)), List.of(globalFilter));

        List<GitProxyFilter> all = source.getAllFilters();
        assertEquals(2, all.size());
        assertTrue(all.contains(globalFilter));
        assertTrue(all.contains(providerFilter));
    }

    @Test
    void filterConfig_noArgConstructor_returnsEmpty() {
        var source = new InMemoryFilterConfigurationSource();
        assertTrue(source.getFiltersForProvider("github").isEmpty());
        assertTrue(source.getAllFilters().isEmpty());
    }

    // --- InMemoryProviderConfigurationSource ---

    @Test
    void providerConfig_fromMap_getProvider_returnsCorrect() {
        GitProxyProvider github = new GitHubProvider("/proxy");
        var source = new InMemoryProviderConfigurationSource(Map.of("github", github));
        assertSame(github, source.getProvider("github"));
    }

    @Test
    void providerConfig_fromMap_unknownProvider_returnsNull() {
        var source = new InMemoryProviderConfigurationSource(Map.of());
        assertNull(source.getProvider("unknown"));
    }

    @Test
    void providerConfig_fromList_getProvider_returnsCorrect() {
        GitProxyProvider github = new GitHubProvider("/proxy");
        GitProxyProvider gitlab = new GitLabProvider("/proxy");
        var source = new InMemoryProviderConfigurationSource(List.of(github, gitlab));
        assertSame(github, source.getProvider("github"));
        assertSame(gitlab, source.getProvider("gitlab"));
    }

    @Test
    void providerConfig_fromList_getProviders_returnsAll() {
        GitProxyProvider github = new GitHubProvider("/proxy");
        GitProxyProvider gitlab = new GitLabProvider("/proxy");
        var source = new InMemoryProviderConfigurationSource(List.of(github, gitlab));
        assertEquals(2, source.getProviders().size());
        assertTrue(source.getProviders().containsAll(List.of(github, gitlab)));
    }

    @Test
    void providerConfig_getProviders_returnsDefensiveCopy() {
        GitProxyProvider github = new GitHubProvider("/proxy");
        var source = new InMemoryProviderConfigurationSource(List.of(github));
        List<GitProxyProvider> list1 = source.getProviders();
        List<GitProxyProvider> list2 = source.getProviders();
        assertNotSame(list1, list2);
    }
}
