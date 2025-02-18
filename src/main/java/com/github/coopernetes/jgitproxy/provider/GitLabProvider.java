package com.github.coopernetes.jgitproxy.provider;

import com.github.coopernetes.jgitproxy.provider.client.GitLabApi;
import java.net.URI;

public final class GitLabProvider extends AbstractGitProxyProvider implements GitLabApi {

    public static final URI DEFAULT_URI = URI.create("https://gitlab.com");
    public static final String NAME = "gitlab";

    /** Default constructor for proxying Gitlab.com */
    public GitLabProvider() {
        super(NAME, DEFAULT_URI);
    }

    /**
     * To support the global proxying of a self-hosted Gitlab instance (e.g. Gitlab CE, Gitlab EE)
     *
     * @param targetUri the URI of the Gitlab instance
     */
    public GitLabProvider(URI targetUri) {
        super(NAME, targetUri);
    }

    /**
     * To support the proxying of additional Gitlab instances
     *
     * @param name the name of the Gitlab instance
     * @param uri the URI of the Gitlab instance
     */
    public GitLabProvider(String name, URI uri) {
        super(name, uri);
    }

    /**
     * To support the proxying of additional Gitlab instances with a custom path
     *
     * @param name the name of the Gitlab instance
     * @param uri the URI of the Gitlab instance
     * @param customPath the custom path to the Gitlab instance
     */
    public GitLabProvider(String name, URI uri, String customPath) {
        super(name, uri, customPath);
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
