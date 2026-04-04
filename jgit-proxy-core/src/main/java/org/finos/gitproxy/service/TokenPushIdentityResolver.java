package org.finos.gitproxy.service;

import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.user.UserEntry;

/**
 * Resolves push identity by calling the provider's user API with the token, then matching the returned SCM username
 * against {@code user_scm_identities} in the user store.
 *
 * <p>Example flow for GitHub:
 *
 * <ol>
 *   <li>Call {@code GET https://api.github.com/user} with {@code Authorization: token <pat>}
 *   <li>Extract {@code login} from the JSON response
 *   <li>Look up {@code ScmIdentity(provider=github, scm_username=login)} → {@link UserEntry}
 *   <li>Cache {@code token → UserEntry} with a short TTL to avoid per-push API calls
 * </ol>
 *
 * <p><b>Not yet implemented.</b> This stub always returns empty. It will be wired in once provider-specific HTTP
 * clients and the SCM identity linking flow are in place. OAuth integration will further simplify this by making the
 * per-push API call unnecessary (identity is established at link-time via the OAuth flow).
 *
 * <p>Providers to support: GitHub ({@code /user}), GitLab ({@code /api/v4/user}).
 */
@Slf4j
public class TokenPushIdentityResolver implements PushIdentityResolver {

    @Override
    public Optional<UserEntry> resolve(String provider, String pushUsername, String token) {
        // TODO: implement token-based identity resolution
        //   1. Check cache: if token is known, return cached UserEntry directly
        //   2. Call provider API: GET /user (GitHub) or /api/v4/user (GitLab) with token
        //   3. Extract login/username from response
        //   4. Look up UserStore.findByScmIdentity(provider, login)
        //   5. Cache result with TTL; cache negative results too to avoid hammering the API
        log.debug("Token-based identity resolution not yet implemented for provider '{}' — returning empty", provider);
        return Optional.empty();
    }
}
