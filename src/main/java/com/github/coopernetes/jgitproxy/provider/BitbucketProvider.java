package com.github.coopernetes.jgitproxy.provider;

import com.github.coopernetes.jgitproxy.provider.client.BitbucketApi;
import java.net.URI;

public final class BitbucketProvider extends AbstractGitProxyProvider implements BitbucketApi {

    public static final URI DEFAULT_URI = URI.create("https://bitbucket.com");
    public static final String NAME = "bitbucket";

    public BitbucketProvider() {
        super(NAME, DEFAULT_URI);
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
