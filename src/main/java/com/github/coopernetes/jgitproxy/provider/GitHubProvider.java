package com.github.coopernetes.jgitproxy.provider;

import com.github.coopernetes.jgitproxy.provider.client.GitHubApi;
import com.github.coopernetes.jgitproxy.provider.client.GitHubClient;
import java.net.URI;

public final class GitHubProvider extends AbstractGitProxyProvider implements GitHubApi {

    public static final URI DEFAULT_URI = URI.create("https://github.com");
    public static final String NAME = "github";

    public GitHubProvider() {
        super(NAME, DEFAULT_URI);
    }

    /**
     * To support custom GitHub Enterprise instances
     *
     * @param targetUri
     */
    public GitHubProvider(URI targetUri) {
        super(NAME, targetUri);
    }

    @Override
    public String getApiUrl() {
        return uri.equals(DEFAULT_URI) ? "https://api.github.com" : String.format("%s/api/v3", uri);
    }

    @Override
    public String getGraphqlUrl() {
        return uri.equals(DEFAULT_URI) ? "https://api.github.com/graphql" : String.format("%s/api/graphql", uri);
    }

    @Override
    public GitHubClient getRestClient() {
        return new GitHubClient(getApiUrl());
    }
}
