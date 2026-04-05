package org.finos.gitproxy.provider;

import jakarta.annotation.Nullable;
import java.net.URI;
import java.util.Base64;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.fluent.Request;
import org.finos.gitproxy.provider.client.BitbucketUserInfo;
import org.finos.gitproxy.provider.client.ScmUserInfo;
import tools.jackson.databind.json.JsonMapper;

/**
 * Provider for Bitbucket Cloud (bitbucket.org) and self-hosted Bitbucket Data Center.
 *
 * <h2>Credential rewriting</h2>
 *
 * <p>Bitbucket does not validate the HTTP Basic-auth username on git pushes — only the token is checked. The proxy
 * adopts the convention that the <b>username field in the remote URL must be the user's Bitbucket account email</b>:
 *
 * <pre>  https://&lt;email&gt;:&lt;api-token&gt;@bitbucket.org/&lt;workspace&gt;/&lt;repo&gt;.git</pre>
 *
 * <p>The proxy calls {@code GET /2.0/user} with {@code Basic email:token} to retrieve the account's auto-generated,
 * URL-safe {@code username} (e.g. {@code a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6}) — the identifier Bitbucket's git endpoint
 * actually accepts. Outbound credentials are then rewritten from {@code email:token} to {@code username:token} before
 * the push is forwarded upstream:
 *
 * <ul>
 *   <li><b>Transparent proxy:</b> {@code BitbucketIdentityFilter} sets {@code upstreamUsername} on
 *       {@link org.finos.gitproxy.git.GitRequestDetails}; {@code PushFinalizerFilter} rewrites the HTTP
 *       {@code Authorization} header via an {@code HttpServletRequestWrapper} before handing off to the proxy servlet.
 *   <li><b>Store-and-forward:</b> {@code BitbucketCredentialRewriteHook} writes the resolved username into the
 *       in-memory JGit repo config; {@code ForwardingPostReceiveHook} reads it and opens the upstream transport with
 *       rewritten {@link org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider}.
 * </ul>
 *
 * <p><b>Required token scopes:</b> {@code read:user:bitbucket} (identity lookup) and {@code write:repository:bitbucket}
 * (git push).
 */
@Slf4j
public class BitbucketProvider extends AbstractGitProxyProvider implements TokenIdentityProvider {

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
     * @see <a href="https://developer.atlassian.com/cloud/bitbucket/rest/">Bitbucket Cloud reference</a>
     * @see <a href="https://developer.atlassian.com/server/bitbucket/reference/api-changelog/">Bitbucket Data Center
     *     reference</a>
     */
    public String getApiUrl() {
        return uri.equals(DEFAULT_URI) ? "https://api.bitbucket.org/2.0" : String.format("%s/rest/api/1.0", uri);
    }

    /** @see <a href="https://developer.atlassian.com/cloud/bitbucket/oauth-2/">Bitbucket Cloud OAuth2 Reference</a> */
    public String getOAuthUrl() {
        return String.format("%s/site/oauth2", uri);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Calls {@code GET /2.0/user} using {@code Basic base64(pushUsername:token)} where {@code pushUsername} is the
     * Bitbucket account email supplied as the git push HTTP Basic-auth username. The response {@code username} field
     * (Bitbucket's auto-generated, URL-safe account identifier) is returned as the SCM login.
     *
     * <p>Required token scopes: {@code read:user:bitbucket} (Bitbucket Cloud).
     */
    @Override
    public Optional<ScmUserInfo> fetchScmIdentity(String pushUsername, String token) {
        if (pushUsername == null || pushUsername.isBlank()) {
            log.warn("Bitbucket identity lookup requires an email as the push username — none provided");
            return Optional.empty();
        }
        try {
            String credentials = Base64.getEncoder().encodeToString((pushUsername + ":" + token).getBytes());
            var response = Request.get(getApiUrl() + "/user")
                    .addHeader("Authorization", "Basic " + credentials)
                    .execute()
                    .returnContent()
                    .asString();
            var info = new JsonMapper().readValue(response, BitbucketUserInfo.class);
            return Optional.of(new ScmUserInfo(info.username(), Optional.empty()));
        } catch (Exception e) {
            log.warn(
                    "Failed to fetch Bitbucket identity for '{}' (invalid token, wrong email, or missing scope?): {}",
                    pushUsername,
                    e.getMessage());
            return Optional.empty();
        }
    }
}
