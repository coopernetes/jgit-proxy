package org.finos.gitproxy.provider;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderTest {

    // --- GitHubProvider ---

    @Test
    void gitHub_defaultUri_servletPath() {
        var p = new GitHubProvider("/proxy");
        assertEquals("/proxy/github.com", p.servletPath());
        assertEquals("/proxy/github.com/*", p.servletMapping());
    }

    @Test
    void gitHub_customPath_overridesHostname() {
        var p = new GitHubProvider(GitHubProvider.DEFAULT_URI, "/proxy", "/gh");
        assertEquals("/proxy/gh", p.servletPath());
    }

    @Test
    void gitHub_defaultUri_apiUrls() {
        var p = new GitHubProvider("/proxy");
        assertEquals("https://api.github.com", p.getApiUrl());
        assertEquals("https://api.github.com/graphql", p.getGraphqlUrl());
    }

    @Test
    void gitHub_enterpriseUri_apiUrls() {
        var p = GitHubProvider.builder()
                .uri(URI.create("https://github.example.com"))
                .basePath("/proxy")
                .build();
        assertEquals("https://github.example.com/api/v3", p.getApiUrl());
        assertEquals("https://github.example.com/api/graphql", p.getGraphqlUrl());
    }

    @Test
    void gitHub_getName() {
        assertEquals("github", new GitHubProvider("/proxy").getName());
    }

    // --- GitLabProvider ---

    @Test
    void gitLab_defaultUri_servletPath() {
        var p = new GitLabProvider("/proxy");
        assertEquals("/proxy/gitlab.com", p.servletPath());
        assertEquals("/proxy/gitlab.com/*", p.servletMapping());
    }

    @Test
    void gitLab_apiUrl() {
        var p = new GitLabProvider("/proxy");
        assertEquals("https://gitlab.com/api/v4", p.getApiUrl());
    }

    @Test
    void gitLab_graphqlUrl() {
        var p = new GitLabProvider("/proxy");
        assertEquals("https://gitlab.com/api/graphql", p.getGraphqlUrl());
    }

    @Test
    void gitLab_oauthUrl() {
        var p = new GitLabProvider("/proxy");
        assertEquals("https://gitlab.com/oauth", p.getOAuthUrl());
    }

    @Test
    void gitLab_getName() {
        assertEquals("gitlab", new GitLabProvider("/proxy").getName());
    }

    // --- BitbucketProvider ---

    @Test
    void bitbucket_defaultUri_servletPath() {
        var p = new BitbucketProvider("/proxy");
        assertEquals("/proxy/bitbucket.org", p.servletPath());
    }

    @Test
    void bitbucket_defaultUri_apiUrl() {
        var p = new BitbucketProvider("/proxy");
        assertEquals("https://api.bitbucket.org/2.0", p.getApiUrl());
    }

    @Test
    void bitbucket_hostedUri_apiUrl() {
        var p = new BitbucketProvider.Builder()
                .uri(URI.create("https://bitbucket.example.com"))
                .basePath("/proxy")
                .build();
        assertEquals("https://bitbucket.example.com/rest/api/1.0", p.getApiUrl());
    }

    @Test
    void bitbucket_oauthUrl() {
        var p = new BitbucketProvider("/proxy");
        assertEquals("https://bitbucket.org/site/oauth2", p.getOAuthUrl());
    }

    @Test
    void bitbucket_getName() {
        assertEquals("bitbucket", new BitbucketProvider("/proxy").getName());
    }

    // --- GenericProxyProvider ---

    @Test
    void generic_servletPath_usesCustomPath() {
        var p = GenericProxyProvider.builder()
                .name("internal-git")
                .uri(URI.create("https://git.internal.example.com"))
                .basePath("/proxy")
                .customPath("/internal")
                .build();
        assertEquals("/proxy/internal", p.servletPath());
        assertEquals("internal-git", p.getName());
    }

    @Test
    void generic_noCustomPath_usesHostname() {
        var p = GenericProxyProvider.builder()
                .name("my-git")
                .uri(URI.create("https://git.corp.com"))
                .basePath("/proxy")
                .build();
        assertEquals("/proxy/git.corp.com", p.servletPath());
    }

    // --- InMemoryProviderRepository ---

    @Test
    void inMemoryRepo_getProvider_returnsCorrectProvider() {
        var github = new GitHubProvider("/proxy");
        var repo = new InMemoryProviderRepository(Map.of("github", github));
        assertSame(github, repo.getProvider("github"));
    }

    @Test
    void inMemoryRepo_getProvider_unknownName_returnsNull() {
        var repo = new InMemoryProviderRepository(Map.of());
        assertNull(repo.getProvider("unknown"));
    }

    @Test
    void inMemoryRepo_getProviders_returnsAll() {
        var github = new GitHubProvider("/proxy");
        var gitlab = new GitLabProvider("/proxy");
        var repo = new InMemoryProviderRepository(Map.of("github", github, "gitlab", gitlab));
        assertEquals(2, repo.getProviders().size());
        assertTrue(repo.getProviders().containsAll(List.of(github, gitlab)));
    }

    @Test
    void inMemoryRepo_getProviders_empty() {
        var repo = new InMemoryProviderRepository(Map.of());
        assertTrue(repo.getProviders().isEmpty());
    }
}
