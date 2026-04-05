package org.finos.gitproxy.provider;

import java.net.URI;
import java.util.Optional;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.fluent.Request;
import org.finos.gitproxy.provider.client.ForgejoUserInfo;
import org.finos.gitproxy.provider.client.ScmUserInfo;
import tools.jackson.databind.json.JsonMapper;

/**
 * Provider for Forgejo instances, including the public Codeberg host ({@code codeberg.org}).
 *
 * <p>Forgejo exposes a Gitea-compatible REST API. Identity resolution calls {@code GET /api/v1/user} with a personal
 * access token and returns both the {@code login} and {@code email} fields.
 *
 * <p>The built-in config name is {@code codeberg} (pointing at {@code https://codeberg.org}) since Codeberg is the
 * largest public Forgejo host. Self-hosted Forgejo instances should use {@code type: forgejo} with an explicit
 * {@code uri}.
 */
@Slf4j
public class ForgejoProvider extends AbstractGitProxyProvider implements TokenIdentityProvider {

    public static final URI DEFAULT_URI = URI.create("https://codeberg.org");
    public static final String NAME = "codeberg";

    @Builder
    public ForgejoProvider(URI uri, String basePath, String customPath) {
        super(NAME, uri, basePath, customPath);
    }

    public ForgejoProvider(String basePath) {
        super(NAME, DEFAULT_URI, basePath);
    }

    public String getApiUrl() {
        return String.format("%s/api/v1", uri);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Calls {@code GET /api/v1/user} with {@code Authorization: token <pat>}. Forgejo returns both {@code login} and
     * {@code email}, so both fields are populated.
     *
     * <p>Required token scope: {@code read:user}.
     */
    @Override
    public Optional<ScmUserInfo> fetchScmIdentity(String pushUsername, String token) {
        try {
            var response = Request.get(getApiUrl() + "/user")
                    .addHeader("Authorization", "token " + token)
                    .execute()
                    .returnContent()
                    .asString();
            var info = new JsonMapper().readValue(response, ForgejoUserInfo.class);
            return Optional.of(new ScmUserInfo(info.login(), Optional.ofNullable(info.email())));
        } catch (Exception e) {
            log.warn("Failed to fetch Forgejo identity (missing scope or invalid token?): {}", e.getMessage());
            return Optional.empty();
        }
    }
}
