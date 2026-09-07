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
 * JDBC-backed cache mapping a GitLab numeric project ID to {@code owner/repo}, with a configurable max age.
 *
 * <p>Entries expire once their {@code cached_at} timestamp is older than the configured {@link Duration}: ignored on
 * read, overwritten on the next successful resolution — the same pattern as
 * {@link com.rbc.fogwall.service.JdbcScmTokenCache} and {@link JdbcGitHubNodeIdCache}.
 */
public class JdbcGitLabProjectIdCache implements GitLabProjectIdCache {

    private static final Logger log = LoggerFactory.getLogger(JdbcGitLabProjectIdCache.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Duration maxAge;

    public JdbcGitLabProjectIdCache(DataSource dataSource, Duration maxAge) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.maxAge = maxAge;
    }

    @Override
    public Optional<OwnerRepo> lookup(String provider, String projectId) {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(maxAge));
        List<OwnerRepo> rows = jdbc.query(
                "SELECT repo_owner, repo_name FROM scm_api_gitlab_project_cache"
                        + " WHERE provider = :provider AND project_id = :projectId AND cached_at >= :cutoff",
                Map.of("provider", provider, "projectId", projectId, "cutoff", cutoff),
                (rs, rowNum) -> new OwnerRepo(rs.getString("repo_owner"), rs.getString("repo_name")));
        if (rows.isEmpty()) return Optional.empty();
        log.debug("GitLab project-ID cache hit: provider={}", provider);
        return Optional.of(rows.get(0));
    }

    @Override
    public void store(String provider, String projectId, OwnerRepo ownerRepo) {
        var params = Map.of(
                "provider", provider,
                "projectId", projectId,
                "owner", ownerRepo.owner(),
                "name", ownerRepo.name(),
                "now", Timestamp.from(Instant.now()));
        tx.executeWithoutResult(status -> {
            jdbc.update(
                    "DELETE FROM scm_api_gitlab_project_cache WHERE provider = :provider AND project_id = :projectId",
                    params);
            jdbc.update(
                    "INSERT INTO scm_api_gitlab_project_cache (provider, project_id, repo_owner, repo_name, cached_at)"
                            + " VALUES (:provider, :projectId, :owner, :name, :now)",
                    params);
        });
        log.debug(
                "GitLab project-ID cached: provider={}, owner={}, name={}",
                provider,
                ownerRepo.owner(),
                ownerRepo.name());
    }
}
