package org.finos.gitproxy.provider;

import java.net.URI;
import org.finos.gitproxy.provider.client.GitHubApi;
import org.finos.gitproxy.provider.client.GitHubClient;

public class GitHubProvider extends AbstractGitProxyProvider implements GitHubApi {

    public static final String NAME = "github";
    public static final URI DEFAULT_URI = URI.create("https://github.com");

    public GitHubProvider(URI uri, String basePath, String customPath) {
        super(NAME, uri, basePath, customPath);
    }

    public GitHubProvider(String basePath) {
        super(NAME, DEFAULT_URI, basePath);
    }

    public static class Builder {
        private URI uri = DEFAULT_URI;
        private String basePath;
        private String customPath;

        public GitHubProvider.Builder uri(URI uri) {
            this.uri = uri;
            return this;
        }

        public GitHubProvider.Builder basePath(String basePath) {
            this.basePath = basePath;
            return this;
        }

        public GitHubProvider.Builder customPath(String customPath) {
            this.customPath = customPath;
            return this;
        }

        public GitHubProvider build() {
            return new GitHubProvider(this.uri, this.basePath, this.customPath);
        }
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
