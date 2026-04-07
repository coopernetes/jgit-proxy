package org.finos.gitproxy.provider;

import java.net.URI;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.fluent.Request;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
public class GitLabProvider extends AbstractGitProxyProvider implements TokenIdentityProvider {

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

    public String getApiUrl() {
        return String.format("%s/api/v4", uri);
    }

    public String getGraphqlUrl() {
        return String.format("%s/api/graphql", uri);
    }

    public String getOAuthUrl() {
        return String.format("%s/oauth", uri);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Calls {@code GET /api/v4/user} with {@code Authorization: Bearer <token>}. GitLab returns the primary email
     * address even when the user has set it to not show on their profile.
     *
     * <p>Required token scope: {@code read_user} or {@code api}.
     */
    @Override
    public Optional<ScmUserInfo> fetchScmIdentity(String pushUsername, String token) {
        try {
            var response = Request.get(getApiUrl() + "/user")
                    .addHeader("Authorization", "Bearer " + token)
                    .execute()
                    .returnContent()
                    .asString();
            var info = new JsonMapper().readValue(response, GitLabUserInfo.class);
            return Optional.of(new ScmUserInfo(info.username(), Optional.ofNullable(info.email())));
        } catch (Exception e) {
            log.warn("Failed to fetch GitLab identity (missing scope or invalid token?): {}", e.getMessage());
            return Optional.empty();
        }
    }
}

/** Jackson deserialization target for the GitLab {@code GET /api/v4/user} response. */
record GitLabUserInfo(
        String username,
        Long id,
        // GitLab's API seems to return the default email address even if profile settings are set to "Do not show on
        // profile". This needs to be verified with more testing. This was tested with one account and the email
        // returned in the response was the primary email (not any secondary or committer emails).
        String email) {}
