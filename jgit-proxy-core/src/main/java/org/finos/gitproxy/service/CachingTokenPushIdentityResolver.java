package org.finos.gitproxy.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.user.UserEntry;
import org.finos.gitproxy.user.UserStore;

/**
 * A {@link PushIdentityResolver} that caches successful token-based identity resolutions to avoid repeated SCM API
 * calls for the same token.
 *
 * <p>The cache key is the SHA-512 digest of {@code "providerName:token"} — the raw token is never stored. On a cache
 * hit the delegate is bypassed entirely and the proxy user is fetched directly from the {@link UserStore}. On a miss
 * the delegate is invoked; a successful resolution is stored in the cache for future pushes.
 *
 * <p>Only positive results are cached. An empty result (bad token, SCM API error, user not found) is never written to
 * the cache, so transient failures do not block subsequent pushes.
 */
@RequiredArgsConstructor
@Slf4j
public class CachingTokenPushIdentityResolver implements PushIdentityResolver {

    private final PushIdentityResolver delegate;
    private final JdbcScmTokenCache cache;
    private final UserStore userStore;

    @Override
    public Optional<UserEntry> resolve(GitProxyProvider provider, String pushUsername, String token) {
        if (provider == null || token == null) {
            return delegate.resolve(provider, pushUsername, token);
        }

        String tokenHash = sha512(provider.getName() + ":" + token);
        Optional<String> cached = cache.lookup(provider.getName(), tokenHash);
        if (cached.isPresent()) {
            return userStore.findByUsername(cached.get());
        }

        Optional<UserEntry> result = delegate.resolve(provider, pushUsername, token);
        result.ifPresent(entry -> cache.store(provider.getName(), tokenHash, entry.getUsername()));
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
