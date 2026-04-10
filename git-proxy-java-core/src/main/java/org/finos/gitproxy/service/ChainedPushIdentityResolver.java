package org.finos.gitproxy.service;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.user.UserEntry;

/**
 * Resolves push identity by trying a list of {@link PushIdentityResolver}s in order, returning the first non-empty
 * result.
 *
 * <p>Intended for multi-SCM environments where identity must be verified against different upstream sources depending
 * on the user — for example, subsidiary developers whose tokens belong to a separate SCM instance (see
 * coopernetes/git-proxy-java#125). Token resolution via the upstream provider is always the implicit default.
 */
@Slf4j
@RequiredArgsConstructor
public class ChainedPushIdentityResolver implements PushIdentityResolver {

    private final List<PushIdentityResolver> chain;

    @Override
    public Optional<UserEntry> resolve(GitProxyProvider provider, String pushUsername, String token) {
        for (PushIdentityResolver resolver : chain) {
            Optional<UserEntry> result = resolver.resolve(provider, pushUsername, token);
            if (result.isPresent()) {
                log.debug(
                        "Push user '{}' resolved by {}",
                        pushUsername,
                        resolver.getClass().getSimpleName());
                return result;
            }
        }
        return Optional.empty();
    }
}
