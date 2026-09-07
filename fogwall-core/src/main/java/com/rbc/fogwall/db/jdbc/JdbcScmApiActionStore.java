package com.rbc.fogwall.db.jdbc;

import com.rbc.fogwall.db.ScmApiActionStore;
import com.rbc.fogwall.db.model.ScmApiActionQuery;
import com.rbc.fogwall.db.model.ScmApiActionRecord;
import com.rbc.fogwall.db.model.ScmApiActionStatus;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * JDBC-based {@link ScmApiActionStore} implementation. Works with H2, PostgreSQL, MySQL, and MariaDB.
 *
 * <p>Records are write-once — a mutation's decision and outcome are known synchronously in one pass, so unlike
 * {@link JdbcPushStore} there is no update path to support.
 */
public class JdbcScmApiActionStore implements ScmApiActionStore {

    private final DataSource dataSource;
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcScmApiActionStore(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public void initialize() {
        DatabaseMigrator.migrate(dataSource);
    }

    @Override
    public void save(ScmApiActionRecord record) {
        jdbc.update("""
                INSERT INTO scm_api_action_records (id, timestamp, provider, scm_username, resolved_user, repo_owner,
                    repo_name, mutation_field, node_id, node_type, status, reason, variables_json,
                    user_agent, client_type, client_version)
                VALUES (:id, :timestamp, :provider, :scmUsername, :resolvedUser, :repoOwner, :repoName, :mutationField,
                    :nodeId, :nodeType, :status, :reason, :variablesJson,
                    :userAgent, :clientType, :clientVersion)
                """, toParams(record));
    }

    @Override
    public Optional<ScmApiActionRecord> findById(String id) {
        List<ScmApiActionRecord> rows =
                jdbc.query("SELECT * FROM scm_api_action_records WHERE id = :id", Map.of("id", id), ROW_MAPPER);
        return rows.stream().findFirst();
    }

    @Override
    public List<ScmApiActionRecord> find(ScmApiActionQuery query) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = buildWhere(query, params);
        String sql = "SELECT * FROM scm_api_action_records" + where
                + " ORDER BY timestamp " + (query.isNewestFirst() ? "DESC" : "ASC")
                + " LIMIT :limit OFFSET :offset";
        params.addValue("limit", query.getLimit());
        params.addValue("offset", query.getOffset());
        return jdbc.query(sql, params, ROW_MAPPER);
    }

    private static String buildWhere(ScmApiActionQuery query, MapSqlParameterSource params) {
        StringBuilder sql = new StringBuilder(" WHERE 1=1");

        if (query.getStatus() != null) {
            sql.append(" AND status = :status");
            params.addValue("status", query.getStatus().name());
        }
        if (query.getProvider() != null) {
            sql.append(" AND provider = :provider");
            params.addValue("provider", query.getProvider());
        }
        if (query.getUser() != null) {
            sql.append(" AND resolved_user = :user");
            params.addValue("user", query.getUser());
        }
        if (query.getRepoOwner() != null) {
            sql.append(" AND repo_owner = :repoOwner");
            params.addValue("repoOwner", query.getRepoOwner());
        }
        if (query.getRepoName() != null) {
            sql.append(" AND repo_name = :repoName");
            params.addValue("repoName", query.getRepoName());
        }
        if (query.getSearch() != null && !query.getSearch().isBlank()) {
            sql.append(" AND (LOWER(repo_owner) LIKE :search OR LOWER(repo_name) LIKE :search)");
            params.addValue("search", "%" + query.getSearch().toLowerCase() + "%");
        }

        return sql.toString();
    }

    private static final RowMapper<ScmApiActionRecord> ROW_MAPPER = (rs, rowNum) -> ScmApiActionRecord.builder()
            .id(rs.getString("id"))
            .timestamp(rs.getTimestamp("timestamp").toInstant())
            .provider(rs.getString("provider"))
            .scmUsername(rs.getString("scm_username"))
            .resolvedUser(rs.getString("resolved_user"))
            .repoOwner(rs.getString("repo_owner"))
            .repoName(rs.getString("repo_name"))
            .mutationField(rs.getString("mutation_field"))
            .nodeId(rs.getString("node_id"))
            .nodeType(rs.getString("node_type"))
            .status(ScmApiActionStatus.valueOf(rs.getString("status")))
            .reason(rs.getString("reason"))
            .variablesJson(rs.getString("variables_json"))
            .userAgent(rs.getString("user_agent"))
            .clientType(rs.getString("client_type"))
            .clientVersion(rs.getString("client_version"))
            .build();

    private static MapSqlParameterSource toParams(ScmApiActionRecord r) {
        return new MapSqlParameterSource()
                .addValue("id", r.getId())
                .addValue("timestamp", Timestamp.from(r.getTimestamp()))
                .addValue("provider", r.getProvider())
                .addValue("scmUsername", r.getScmUsername())
                .addValue("resolvedUser", r.getResolvedUser())
                .addValue("repoOwner", r.getRepoOwner())
                .addValue("repoName", r.getRepoName())
                .addValue("mutationField", r.getMutationField())
                .addValue("nodeId", r.getNodeId())
                .addValue("nodeType", r.getNodeType())
                .addValue("status", r.getStatus().name())
                .addValue("reason", r.getReason())
                .addValue("variablesJson", r.getVariablesJson())
                .addValue("userAgent", r.getUserAgent())
                .addValue("clientType", r.getClientType())
                .addValue("clientVersion", r.getClientVersion());
    }
}
