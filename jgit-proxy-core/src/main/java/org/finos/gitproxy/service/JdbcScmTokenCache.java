package org.finos.gitproxy.service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * JDBC-backed cache for SCM token identity resolutions.
 *
 * <p>Stores a mapping of {@code (provider, SHA-512(provider:token)) -> proxy_username} with a configurable max age.
 * Entries are considered expired once their {@code cached_at} timestamp is older than the configured {@link Duration}.
 * Expired entries are ignored on read and overwritten on the next successful resolution.
 *
 * <p>Only positive resolutions are cached — a failed or empty SCM API response is never stored.
 */
public class JdbcScmTokenCache {

    private static final Logger log = LoggerFactory.getLogger(JdbcScmTokenCache.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final Duration maxAge;

    public JdbcScmTokenCache(DataSource dataSource, Duration maxAge) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.maxAge = maxAge;
    }

    /**
     * Looks up a cached proxy username for the given provider and token hash.
     *
     * @param provider the provider name (e.g. "github")
     * @param tokenHash SHA-512 hex digest of {@code provider:token}
     * @return the cached proxy username, or empty if not present or expired
     */
    public Optional<String> lookup(String provider, String tokenHash) {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(maxAge));
        List<String> rows = jdbc.queryForList(
                "SELECT proxy_username FROM scm_token_cache"
                        + " WHERE token_hash = :hash AND provider = :provider AND cached_at >= :cutoff",
                Map.of("hash", tokenHash, "provider", provider, "cutoff", cutoff),
                String.class);
        if (rows.isEmpty()) return Optional.empty();
        log.debug("SCM token cache hit: provider={}", provider);
        return Optional.of(rows.get(0));
    }

    /**
     * Stores or refreshes a cache entry. Replaces any existing entry for this token hash + provider.
     *
     * @param provider the provider name
     * @param tokenHash SHA-512 hex digest of {@code provider:token}
     * @param proxyUsername the resolved proxy username to cache
     */
    /**
     * Evicts all cache entries for the given proxy username and provider. Call this whenever an SCM identity is added
     * or removed so that stale token→user mappings are not served from cache.
     *
     * @param provider the provider name
     * @param proxyUsername the proxy username whose cache entries should be removed
     */
    public void evictByUsername(String provider, String proxyUsername) {
        int deleted = jdbc.update(
                "DELETE FROM scm_token_cache WHERE provider = :provider AND proxy_username = :username",
                Map.of("provider", provider, "username", proxyUsername));
        if (deleted > 0) {
            log.debug("SCM token cache evicted: provider={}, user={}, entries={}", provider, proxyUsername, deleted);
        }
    }

    public void store(String provider, String tokenHash, String proxyUsername) {
        jdbc.update(
                "DELETE FROM scm_token_cache WHERE token_hash = :hash AND provider = :provider",
                Map.of("hash", tokenHash, "provider", provider));
        jdbc.update(
                "INSERT INTO scm_token_cache (token_hash, provider, proxy_username, cached_at)"
                        + " VALUES (:hash, :provider, :username, :now)",
                Map.of(
                        "hash", tokenHash,
                        "provider", provider,
                        "username", proxyUsername,
                        "now", Timestamp.from(Instant.now())));
        log.debug("SCM token cached: provider={}, user={}", provider, proxyUsername);
    }
}
