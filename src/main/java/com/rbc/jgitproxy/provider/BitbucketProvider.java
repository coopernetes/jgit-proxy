package com.rbc.jgitproxy.provider;

import com.rbc.jgitproxy.provider.client.BitbucketApi;
import jakarta.annotation.Nullable;

import java.net.URI;

public class BitbucketProvider extends AbstractGitProxyProvider implements BitbucketApi {

    public static final URI DEFAULT_URI = URI.create("https://bitbucket.org");
    public static final String NAME = "bitbucket";

    public BitbucketProvider(URI uri, String basePath, @Nullable String customPath) {
        super(NAME, uri, basePath, customPath);
    }

    public BitbucketProvider(String basePath) {
        super(NAME, DEFAULT_URI, basePath);
    }

    public static class Builder {
        private URI uri = DEFAULT_URI;
        private String basePath;
        private String customPath;

        public BitbucketProvider.Builder uri(URI uri) {
            this.uri = uri;
            return this;
        }

        public BitbucketProvider.Builder basePath(String basePath) {
            this.basePath = basePath;
            return this;
        }

        public BitbucketProvider.Builder customPath(String customPath) {
            this.customPath = customPath;
            return this;
        }

        public BitbucketProvider build() {
            return new BitbucketProvider(this.uri, this.basePath, this.customPath);
        }
    }

    /**
     *
     *
     * <ul>
     *   <li><a href="https://developer.atlassian.com/cloud/bitbucket/rest/">Bitbucket Cloud reference</a>
     *   <li><a href="https://developer.atlassian.com/server/bitbucket/reference/api-changelog/">Bitbucket Data Center
     *       reference</a>
     * </ul>
     *
     * @return the URL to the Bitbucket API
     */
    @Override
    public String getApiUrl() {
        return uri.equals(DEFAULT_URI)
                ? "https://api.bitbucket.com/2.0"
                : String.format("%s/rest/api/1.0", uri); // TODO: Verify this URL for hosted Bitbucket instances
    }

    /**
     * <a href="https://developer.atlassian.com/cloud/bitbucket/oauth-2/">Bitbucket Cloud OAuth2 Reference</a>
     *
     * <p>This URL may be different for Bitbucket Data Center instances.
     *
     * @return the URL to the OAuth2 endpoint
     */
    @Override
    public String getOAuthUrl() {
        return String.format("%s/site/oauth2", uri); // TODO: Verify if this only applies for Bitbucket cloud
    }
}
