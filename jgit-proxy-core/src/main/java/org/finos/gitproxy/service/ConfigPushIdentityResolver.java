package org.finos.gitproxy.service;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.user.UserEntry;
import org.finos.gitproxy.user.UserStore;

/**
 * Resolves push identity from the static user config via {@code push-usernames}.
 *
 * <p>Looks up the HTTP Basic-auth username in {@link UserStore#findByPushUsername(String)}, then falls back to
 * {@link UserStore#findByUsername(String)} for the case where the proxy username and push username are the same. The
 * token is ignored — this resolver is purely config-driven and makes no external API calls.
 *
 * <p>This is the default resolver. It is appropriate for environments that do not have token-based identity lookup set
 * up yet, or where push usernames are stable and known in advance (e.g. corporate usernames).
 */
@Slf4j
@RequiredArgsConstructor
public class ConfigPushIdentityResolver implements PushIdentityResolver {

    private final UserStore userStore;

    @Override
    public Optional<UserEntry> resolve(String provider, String pushUsername, String token) {
        if (pushUsername == null || pushUsername.isBlank()) {
            return Optional.empty();
        }
        var byPushUsername = userStore.findByPushUsername(pushUsername);
        if (byPushUsername.isPresent()) {
            log.debug("Resolved push user '{}' via push-usernames config", pushUsername);
            return byPushUsername;
        }
        var byUsername = userStore.findByUsername(pushUsername);
        if (byUsername.isPresent()) {
            log.debug("Resolved push user '{}' via proxy username match", pushUsername);
        }
        return byUsername;
    }
}
