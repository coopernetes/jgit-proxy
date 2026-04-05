package org.finos.gitproxy.provider;

import java.net.URI;
import java.util.Optional;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.fluent.Request;
import org.finos.gitproxy.provider.client.GitHubUserInfo;
import org.finos.gitproxy.provider.client.ScmUserInfo;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
public class GitHubProvider extends AbstractGitProxyProvider implements TokenIdentityProvider {

    public static final String NAME = "github";
    public static final URI DEFAULT_URI = URI.create("https://github.com");

    @Builder
    public GitHubProvider(URI uri, String basePath, String customPath) {
        super(NAME, uri, basePath, customPath);
    }

    public GitHubProvider(String basePath) {
        super(NAME, DEFAULT_URI, basePath);
    }

    public String getApiUrl() {
        if (uri.equals(DEFAULT_URI)) {
            return "https://api.github.com";
        }
        if (isGhe()) {
            return String.format("https://api.%s", uri.getHost());
        }
        // fallback to self-hosted GHES
        return String.format("%s/api/v3", uri);
    }

    public String getGraphqlUrl() {
        if (uri.equals(DEFAULT_URI)) {
            return "https://api.github.com/graphql";
        }
        if (isGhe()) {
            return String.format("https://api.%s/graphql", uri.getHost());
        }
        // fallback to self-hosted GHES
        return String.format("%s/api/graphql", uri);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Calls {@code GET /user} with {@code Authorization: token <pat>}. GitHub emails are often empty because users
     * default to private email visibility — the {@link ScmUserInfo#email()} field will be empty in that case.
     *
     * <p>Required token scope: {@code read:user} (or {@code user} for classic PATs).
     */
    @Override
    public Optional<ScmUserInfo> fetchScmIdentity(String pushUsername, String token) {
        try {
            var response = Request.get(getApiUrl() + "/user")
                    .addHeader("Authorization", "token " + token)
                    .execute()
                    .returnContent()
                    .asString();
            var info = new JsonMapper().readValue(response, GitHubUserInfo.class);
            return Optional.of(new ScmUserInfo(info.login(), info.email()));
        } catch (Exception e) {
            log.warn("Failed to fetch GitHub identity (missing scope or invalid token?): {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Determines if the provider is a GitHub Enterprise Cloud with data residency. These instances have a custom domain
     * (e.g. mycompany.ghe.com) and use a different API path from GHEC or self-hosted GHES.
     *
     * @see <a
     *     href="https://docs.github.com/en/enterprise-cloud@latest/admin/data-residency/network-details-for-ghecom">GHE.com
     *     network documentation</a>
     */
    private boolean isGhe() {
        return uri.getHost().endsWith(".ghe.com");
    }
}
