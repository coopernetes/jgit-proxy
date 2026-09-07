package com.rbc.fogwall.service;

import com.rbc.fogwall.provider.FogwallProvider;
import com.rbc.fogwall.provider.HttpTokenUserLookup;
import com.rbc.fogwall.user.ReadOnlyUserStore;
import com.rbc.fogwall.user.UserEntry;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves push identity by calling the provider's user API with the token, then matching the returned SCM username
 * against {@code user_scm_identities} in the user store.
 *
 * <p>Delegates the HTTP call and identity extraction to the provider via {@link HttpTokenUserLookup}. Each provider
 * handles its own API endpoint, auth header format, and error cases (e.g. missing token scope).
 *
 * <p>Resolution order:
 *
 * <ol>
 *   <li>Call the provider's {@link HttpTokenUserLookup#fetchUserFromHttp} to get the SCM {@code login} and optional
 *       {@code email}.
 *   <li>Look up {@code user_scm_identities} by {@code (provider, login)}.
 *   <li>Fall back to {@code user_emails} lookup using the SCM email (if present).
 * </ol>
 *
 * <p>Providers that do not implement {@link HttpTokenUserLookup} are unsupported and always return empty.
 */
@RequiredArgsConstructor
@Slf4j
public class TokenPushIdentityResolver implements PushIdentityResolver {

    private final ReadOnlyUserStore userStore;

    @Override
    public Optional<UserEntry> resolve(FogwallProvider provider, String pushUsername, String token) {
        return resolveIdentity(provider, pushUsername, token).map(ResolvedScmIdentity::user);
    }

    @Override
    public Optional<ResolvedScmIdentity> resolveIdentity(FogwallProvider provider, String pushUsername, String token) {
        if (!(provider instanceof HttpTokenUserLookup tip)) {
            log.debug(
                    "Token identity lookup not supported for provider '{}' — returning empty",
                    provider != null ? provider.getName() : "null");
            return Optional.empty();
        }

        // The login is kept alongside the user rather than recovered from it later: the match may have been made on
        // email, in which case no identity on file carries this login at all.
        return tip.fetchUserFromHttp(pushUsername, token)
                .flatMap(id -> userStore
                        .findByScmIdentity(provider.getProviderId(), id.login())
                        .or(() -> userStore.findByEmail(id.email().orElse(null)))
                        .map(user -> new ResolvedScmIdentity(user, id.login())));
    }
}
