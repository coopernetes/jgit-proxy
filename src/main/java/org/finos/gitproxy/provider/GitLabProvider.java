package org.finos.gitproxy.provider;

import org.finos.gitproxy.provider.client.GitLabApi;

import java.net.URI;

public class GitLabProvider extends AbstractGitProxyProvider implements GitLabApi {

    public static final URI DEFAULT_URI = URI.create("https://gitlab.com");
    public static final String NAME = "gitlab";

    public GitLabProvider(URI uri, String basePath, String customPath) {
        super(NAME, uri, basePath, customPath);
    }

    public GitLabProvider(String basePath) {
        super(NAME, DEFAULT_URI, basePath);
    }

    public static class Builder {
        private URI uri = DEFAULT_URI;
        private String basePath;
        private String customPath;

        public GitLabProvider.Builder uri(URI uri) {
            this.uri = uri;
            return this;
        }

        public GitLabProvider.Builder basePath(String basePath) {
            this.basePath = basePath;
            return this;
        }

        public GitLabProvider.Builder customPath(String customPath) {
            this.customPath = customPath;
            return this;
        }

        public GitLabProvider build() {
            return new GitLabProvider(this.uri, this.basePath, this.customPath);
        }
    }

    @Override
    public String getApiUrl() {
        return String.format("%s/api/v4", uri);
    }

    @Override
    public String getGraphqlUrl() {
        return String.format("%s/api/graphql", uri);
    }

    @Override
    public String getOAuthUrl() {
        return String.format("%s/oauth", uri);
    }
}
