package org.finos.gitproxy.permission;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** JDBC-backed {@link RepoPermissionStore}. Works with H2, PostgreSQL, and SQLite. */
public class JdbcRepoPermissionStore implements RepoPermissionStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcRepoPermissionStore(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public void save(RepoPermission p) {
        jdbc.update("""
                INSERT INTO repo_permissions (id, username, provider, path, path_type, operations, source)
                VALUES (:id, :username, :provider, :path, :pathType, :operations, :source)
                """, params(p));
    }

    @Override
    public void delete(String id) {
        jdbc.update("DELETE FROM repo_permissions WHERE id = :id", Map.of("id", id));
    }

    @Override
    public Optional<RepoPermission> findById(String id) {
        var rows = jdbc.query("SELECT * FROM repo_permissions WHERE id = :id", Map.of("id", id), ROW_MAPPER);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<RepoPermission> findAll() {
        return jdbc.query("SELECT * FROM repo_permissions ORDER BY provider, path, username", ROW_MAPPER);
    }

    @Override
    public List<RepoPermission> findByUsername(String username) {
        return jdbc.query(
                "SELECT * FROM repo_permissions WHERE username = :username ORDER BY provider, path",
                Map.of("username", username),
                ROW_MAPPER);
    }

    @Override
    public List<RepoPermission> findByProvider(String provider) {
        return jdbc.query(
                "SELECT * FROM repo_permissions WHERE provider = :provider ORDER BY path, username",
                Map.of("provider", provider),
                ROW_MAPPER);
    }

    private static MapSqlParameterSource params(RepoPermission p) {
        return new MapSqlParameterSource()
                .addValue("id", p.getId())
                .addValue("username", p.getUsername())
                .addValue("provider", p.getProvider())
                .addValue("path", p.getPath())
                .addValue("pathType", p.getPathType().name())
                .addValue("operations", p.getOperations().name())
                .addValue("source", p.getSource().name());
    }

    private static final RowMapper<RepoPermission> ROW_MAPPER = JdbcRepoPermissionStore::mapRow;

    private static RepoPermission mapRow(ResultSet rs, int i) throws SQLException {
        return RepoPermission.builder()
                .id(rs.getString("id"))
                .username(rs.getString("username"))
                .provider(rs.getString("provider"))
                .path(rs.getString("path"))
                .pathType(RepoPermission.PathType.valueOf(rs.getString("path_type")))
                .operations(RepoPermission.Operations.valueOf(rs.getString("operations")))
                .source(RepoPermission.Source.valueOf(rs.getString("source")))
                .build();
    }
}
