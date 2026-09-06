package com.rbc.fogwall.service;

import com.rbc.fogwall.provider.FogwallProvider;
import com.rbc.fogwall.user.ReadOnlyUserStore;
import com.rbc.fogwall.user.UserEntry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * A {@link PushIdentityResolver} that caches successful token-based identity resolutions to avoid repeated SCM API
 * calls for the same token.
 *
 * <p>The cache key is the SHA-512 digest of {@code "providerName:token"} — the raw token is never stored. On a cache
 * hit the delegate is bypassed entirely and the proxy user is fetched directly from the {@link ReadOnlyUserStore}. On a
 * miss the delegate is invoked; a successful resolution is stored in the cache for future pushes.
 *
 * <p>The provider login is cached beside the proxy username because the delegate is what learns it, and on a hit the
 * delegate never runs. It cannot be recovered from the proxy user either: a user may hold several identities on one
 * provider. An entry written before the login was cached carries null, and ages out on the cache TTL.
 *
 * <p>Only positive results are cached. An empty result (bad token, SCM API error, user not found) is never written to
 * the cache, so transient failures do not block subsequent pushes.
 */
@RequiredArgsConstructor
@Slf4j
public class CachingTokenPushIdentityResolver implements PushIdentityResolver {

    private final PushIdentityResolver delegate;
    private final ScmTokenCache cache;
    private final ReadOnlyUserStore userStore;

    @Override
    public Optional<UserEntry> resolve(FogwallProvider provider, String pushUsername, String token) {
        return resolveIdentity(provider, pushUsername, token).map(ResolvedScmIdentity::user);
    }

    @Override
    public Optional<ResolvedScmIdentity> resolveIdentity(FogwallProvider provider, String pushUsername, String token) {
        if (provider == null || token == null) {
            return delegate.resolveIdentity(provider, pushUsername, token);
        }

        String tokenHash = sha512(provider.getProviderId() + ":" + token);
        Optional<CachedScmIdentity> cached = cache.lookup(provider.getProviderId(), tokenHash);
        if (cached.isPresent()) {
            return userStore
                    .findByUsername(cached.get().proxyUsername())
                    .map(user -> new ResolvedScmIdentity(user, cached.get().scmLogin()));
        }

        Optional<ResolvedScmIdentity> result = delegate.resolveIdentity(provider, pushUsername, token);
        result.ifPresent(resolved -> cache.store(
                provider.getProviderId(),
                tokenHash,
                new CachedScmIdentity(resolved.user().getUsername(), resolved.scmLogin())));
        return result;
    }

    private static String sha512(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 unavailable", e);
        }
    }
}
