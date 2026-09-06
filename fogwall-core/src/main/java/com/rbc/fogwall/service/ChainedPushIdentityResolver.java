package com.rbc.fogwall.service;

import com.rbc.fogwall.provider.FogwallProvider;
import com.rbc.fogwall.user.UserEntry;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves push identity by trying a list of {@link PushIdentityResolver}s in order, returning the first non-empty
 * result.
 *
 * <p>Intended for multi-SCM environments where identity must be verified against different upstream sources depending
 * on the user — for example, subsidiary developers whose tokens belong to a separate SCM instance (see
 * coopernetes/fogwall#125). Token resolution via the upstream provider is always the implicit default.
 */
@Slf4j
@RequiredArgsConstructor
public class ChainedPushIdentityResolver implements PushIdentityResolver {

    private final List<PushIdentityResolver> chain;

    @Override
    public Optional<UserEntry> resolve(FogwallProvider provider, String pushUsername, String token) {
        return resolveIdentity(provider, pushUsername, token).map(ResolvedScmIdentity::user);
    }

    /** Delegates to each link's own {@code resolveIdentity}, so whichever one answers also supplies its login. */
    @Override
    public Optional<ResolvedScmIdentity> resolveIdentity(FogwallProvider provider, String pushUsername, String token) {
        for (PushIdentityResolver resolver : chain) {
            Optional<ResolvedScmIdentity> result = resolver.resolveIdentity(provider, pushUsername, token);
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
