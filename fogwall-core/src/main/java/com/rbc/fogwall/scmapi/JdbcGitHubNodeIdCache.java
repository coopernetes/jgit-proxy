package com.rbc.fogwall.scmapi;

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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JDBC-backed cache for SCM API proxy node-ID resolutions.
 *
 * <p>Stores a mapping of {@code (provider, node id) -> owner/repo} with a configurable max age. Entries are considered
 * expired once their {@code cached_at} timestamp is older than the configured {@link Duration}. Expired entries are
 * ignored on read and overwritten on the next successful resolution — mirrors
 * {@link com.rbc.fogwall.service.JdbcScmTokenCache}.
 */
public class JdbcGitHubNodeIdCache implements GitHubNodeIdCache {

    private static final Logger log = LoggerFactory.getLogger(JdbcGitHubNodeIdCache.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Duration maxAge;

    public JdbcGitHubNodeIdCache(DataSource dataSource, Duration maxAge) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.maxAge = maxAge;
    }

    @Override
    public Optional<OwnerRepo> lookup(String provider, String nodeId) {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(maxAge));
        List<OwnerRepo> rows = jdbc.query(
                "SELECT repo_owner, repo_name FROM scm_api_github_node_cache"
                        + " WHERE provider = :provider AND node_id = :nodeId AND cached_at >= :cutoff",
                Map.of("provider", provider, "nodeId", nodeId, "cutoff", cutoff),
                (rs, rowNum) -> new OwnerRepo(rs.getString("repo_owner"), rs.getString("repo_name")));
        if (rows.isEmpty()) return Optional.empty();
        log.debug("Node-ID cache hit: provider={}", provider);
        return Optional.of(rows.get(0));
    }

    @Override
    public void store(String provider, String nodeId, OwnerRepo ownerRepo) {
        var params = Map.of(
                "provider", provider,
                "nodeId", nodeId,
                "owner", ownerRepo.owner(),
                "name", ownerRepo.name(),
                "now", Timestamp.from(Instant.now()));
        tx.executeWithoutResult(status -> {
            jdbc.update(
                    "DELETE FROM scm_api_github_node_cache WHERE provider = :provider AND node_id = :nodeId", params);
            jdbc.update(
                    "INSERT INTO scm_api_github_node_cache (provider, node_id, repo_owner, repo_name, cached_at)"
                            + " VALUES (:provider, :nodeId, :owner, :name, :now)",
                    params);
        });
        log.debug("Node-ID cached: provider={}, owner={}, name={}", provider, ownerRepo.owner(), ownerRepo.name());
    }
}
