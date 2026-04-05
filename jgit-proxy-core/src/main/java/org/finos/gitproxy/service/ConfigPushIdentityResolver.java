package org.finos.gitproxy.service;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.user.UserEntry;
import org.finos.gitproxy.user.UserStore;

/**
 * Resolves push identity by matching the HTTP Basic-auth username against proxy usernames in the user store.
 *
 * <p>The token is ignored — this resolver is purely config-driven and makes no external API calls. It is suitable for
 * environments without SCM OAuth / token-based identity lookup, where the git client is configured to use the same
 * username as the proxy account (e.g. corporate SSO username).
 *
 * <p>The provider is also ignored — this resolver works the same regardless of which SCM is being proxied.
 */
@Slf4j
@RequiredArgsConstructor
public class ConfigPushIdentityResolver implements PushIdentityResolver {

    private final UserStore userStore;

    @Override
    public Optional<UserEntry> resolve(GitProxyProvider provider, String pushUsername, String token) {
        if (pushUsername == null || pushUsername.isBlank()) {
            return Optional.empty();
        }
        // Check push-username aliases first (stored as scm identities under the "proxy" provider).
        var result = userStore.findByScmIdentity("proxy", pushUsername);
        if (result.isPresent()) {
            log.debug("Resolved push user '{}' via push-username alias", pushUsername);
            return result;
        }
        // Fall back to direct proxy username match.
        result = userStore.findByUsername(pushUsername);
        if (result.isPresent()) {
            log.debug("Resolved push user '{}' via proxy username match", pushUsername);
        }
        return result;
    }
}
