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
 * <p>The standard chain is: {@link TokenPushIdentityResolver} (provider API lookup) →
 * {@link ConfigPushIdentityResolver} (static push-username aliases). Token resolution is authoritative when the
 * provider supports it; config aliases are the fallback for providers without token identity support or when the API
 * call fails.
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
