package org.finos.gitproxy.db.jdbc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.jdbc.mapper.AttestationRowMapper;
import org.finos.gitproxy.db.jdbc.mapper.PushCommitRowMapper;
import org.finos.gitproxy.db.jdbc.mapper.PushRecordRowMapper;
import org.finos.gitproxy.db.jdbc.mapper.PushStepRowMapper;
import org.finos.gitproxy.db.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JDBC-based {@link PushStore} implementation. Works with H2 (in-memory and file), SQLite, and PostgreSQL.
 *
 * <p>Uses Spring's {@link NamedParameterJdbcTemplate} and {@link TransactionTemplate} — no manual
 * {@code PreparedStatement} or connection management.
 */
public class JdbcPushStore implements PushStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcPushStore.class);
    private static final String SCHEMA_RESOURCE = "/db/schema.sql";

    private final DataSource dataSource;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JdbcPushStore(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public void initialize() {
        String schema = loadSchema();
        JdbcTemplate plain = new JdbcTemplate(dataSource);
        for (String sql : schema.split(";")) {
            String trimmed = sql.trim();
            if (!trimmed.isEmpty()) {
                plain.execute(trimmed);
            }
        }
        log.info("Push store schema initialized");
    }

    @Override
    public void save(PushRecord record) {
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO push_records (id, timestamp, url, upstream_url, project, repo_name, branch,
                        commit_from, commit_to, message, author, author_email, committer, committer_email,
                        push_user, user_email, method, status, error_message, blocked_message,
                        auto_approved, auto_rejected)
                    VALUES (:id, :timestamp, :url, :upstreamUrl, :project, :repoName, :branch,
                        :commitFrom, :commitTo, :message, :author, :authorEmail, :committer, :committerEmail,
                        :user, :userEmail, :method, :status, :errorMessage, :blockedMessage,
                        :autoApproved, :autoRejected)
                    """, pushRecordParams(record));

            saveSteps(record.getId(), record.getSteps());
            saveCommits(record.getId(), record.getCommits());
            if (record.getAttestation() != null) {
                saveAttestation(record.getId(), record.getAttestation());
            }
        });
    }

    @Override
    public Optional<PushRecord> findById(String id) {
        List<PushRecord> rows =
                jdbc.query("SELECT * FROM push_records WHERE id = :id", Map.of("id", id), PushRecordRowMapper.INSTANCE);
        if (rows.isEmpty()) return Optional.empty();
        PushRecord record = rows.get(0);
        hydrate(record);
        return Optional.of(record);
    }

    @Override
    public List<PushRecord> find(PushQuery query) {
        StringBuilder sql = new StringBuilder("SELECT * FROM push_records WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (query.getStatus() != null) {
            sql.append(" AND status = :status");
            params.addValue("status", query.getStatus().name());
        }
        if (query.getProject() != null) {
            sql.append(" AND project = :project");
            params.addValue("project", query.getProject());
        }
        if (query.getRepoName() != null) {
            sql.append(" AND repo_name = :repoName");
            params.addValue("repoName", query.getRepoName());
        }
        if (query.getBranch() != null) {
            sql.append(" AND branch = :branch");
            params.addValue("branch", query.getBranch());
        }
        if (query.getCommitTo() != null) {
            sql.append(" AND commit_to = :commitTo");
            params.addValue("commitTo", query.getCommitTo());
        }
        if (query.getUser() != null) {
            sql.append(" AND push_user = :user");
            params.addValue("user", query.getUser());
        }
        if (query.getAuthorEmail() != null) {
            sql.append(" AND author_email = :authorEmail");
            params.addValue("authorEmail", query.getAuthorEmail());
        }
        if (query.getSearch() != null && !query.getSearch().isBlank()) {
            sql.append(" AND (LOWER(project) LIKE :search OR LOWER(repo_name) LIKE :search)");
            params.addValue("search", "%" + query.getSearch().toLowerCase() + "%");
        }

        sql.append(" ORDER BY timestamp ");
        sql.append(query.isNewestFirst() ? "DESC" : "ASC");
        sql.append(" LIMIT :limit");
        params.addValue("limit", query.getLimit());

        List<PushRecord> results = jdbc.query(sql.toString(), params, PushRecordRowMapper.INSTANCE);
        results.forEach(this::hydrate);
        return results;
    }

    @Override
    public void delete(String id) {
        // CASCADE handles child tables
        jdbc.update("DELETE FROM push_records WHERE id = :id", Map.of("id", id));
    }

    @Override
    public PushRecord approve(String id, Attestation attestation) {
        return updateStatus(id, PushStatus.APPROVED, attestation);
    }

    @Override
    public PushRecord reject(String id, Attestation attestation) {
        return updateStatus(id, PushStatus.REJECTED, attestation);
    }

    @Override
    public PushRecord cancel(String id, Attestation attestation) {
        return updateStatus(id, PushStatus.CANCELED, attestation);
    }

    @Override
    public void close() {
        if (dataSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("Error closing datasource", e);
            }
        }
    }

    // --- Private helpers ---

    private PushRecord updateStatus(String id, PushStatus status, Attestation attestation) {
        tx.executeWithoutResult(txStatus -> {
            int updated = jdbc.update(
                    "UPDATE push_records SET status = :status WHERE id = :id",
                    Map.of("status", status.name(), "id", id));
            if (updated == 0) {
                throw new IllegalArgumentException("Push not found: " + id);
            }
            if (attestation != null) {
                saveAttestation(id, attestation);
            }
        });
        return findById(id).orElseThrow();
    }

    /** Populate the child collections on an already-loaded {@link PushRecord}. */
    private void hydrate(PushRecord record) {
        record.setSteps(loadSteps(record.getId()));
        record.setCommits(loadCommits(record.getId()));
        record.setAttestation(loadAttestation(record.getId()));
    }

    // --- Steps ---

    private void saveSteps(String pushId, List<PushStep> steps) {
        if (steps == null || steps.isEmpty()) return;
        List<Map<String, Object>> batchValues = new ArrayList<>(steps.size());
        for (PushStep step : steps) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", step.getId());
            m.put("pushId", pushId);
            m.put("stepName", step.getStepName());
            m.put("stepOrder", step.getStepOrder());
            m.put("status", step.getStatus().name());
            m.put("content", step.getContent());
            m.put("errorMessage", step.getErrorMessage());
            m.put("blockedMessage", step.getBlockedMessage());
            m.put("logs", PushStepRowMapper.joinLogs(step.getLogs()));
            m.put("timestamp", Timestamp.from(step.getTimestamp()));
            batchValues.add(m);
        }
        jdbc.batchUpdate(
                """
                INSERT INTO push_steps (id, push_id, step_name, step_order, status, content,
                    error_message, blocked_message, logs, timestamp)
                VALUES (:id, :pushId, :stepName, :stepOrder, :status, :content,
                    :errorMessage, :blockedMessage, :logs, :timestamp)
                """, batchValues.stream().map(MapSqlParameterSource::new).toArray(MapSqlParameterSource[]::new));
    }

    private List<PushStep> loadSteps(String pushId) {
        return jdbc.query(
                "SELECT * FROM push_steps WHERE push_id = :pushId ORDER BY step_order",
                Map.of("pushId", pushId),
                PushStepRowMapper.INSTANCE);
    }

    // --- Commits ---

    private void saveCommits(String pushId, List<PushCommit> commits) {
        if (commits == null || commits.isEmpty()) return;
        List<MapSqlParameterSource> batchParams = new ArrayList<>(commits.size());
        for (PushCommit c : commits) {
            List<String> sobs = c.getSignedOffBy();
            batchParams.add(new MapSqlParameterSource()
                    .addValue("pushId", pushId)
                    .addValue("sha", c.getSha())
                    .addValue("parentSha", c.getParentSha())
                    .addValue("authorName", c.getAuthorName())
                    .addValue("authorEmail", c.getAuthorEmail())
                    .addValue("committerName", c.getCommitterName())
                    .addValue("committerEmail", c.getCommitterEmail())
                    .addValue("message", c.getMessage())
                    .addValue("commitDate", c.getCommitDate() != null ? Timestamp.from(c.getCommitDate()) : null)
                    .addValue("signature", c.getSignature())
                    .addValue("signedOffBy", sobs != null && !sobs.isEmpty() ? String.join("\n", sobs) : null));
        }
        jdbc.batchUpdate("""
                INSERT INTO push_commits (push_id, sha, parent_sha, author_name, author_email,
                    committer_name, committer_email, message, commit_date, signature, signed_off_by)
                VALUES (:pushId, :sha, :parentSha, :authorName, :authorEmail,
                    :committerName, :committerEmail, :message, :commitDate, :signature, :signedOffBy)
                """, batchParams.toArray(MapSqlParameterSource[]::new));
    }

    private List<PushCommit> loadCommits(String pushId) {
        return jdbc.query(
                "SELECT * FROM push_commits WHERE push_id = :pushId",
                Map.of("pushId", pushId),
                PushCommitRowMapper.INSTANCE);
    }

    // --- Attestation ---

    private void saveAttestation(String pushId, Attestation att) {
        jdbc.update(
                """
                INSERT INTO push_attestations (push_id, type, reviewer_username, reviewer_email,
                    reason, automated, timestamp)
                VALUES (:pushId, :type, :reviewerUsername, :reviewerEmail, :reason, :automated, :timestamp)
                """,
                new MapSqlParameterSource()
                        .addValue("pushId", pushId)
                        .addValue("type", att.getType().name())
                        .addValue("reviewerUsername", att.getReviewerUsername())
                        .addValue("reviewerEmail", att.getReviewerEmail())
                        .addValue("reason", att.getReason())
                        .addValue("automated", att.isAutomated())
                        .addValue("timestamp", Timestamp.from(att.getTimestamp())));
    }

    private Attestation loadAttestation(String pushId) {
        List<Attestation> rows = jdbc.query(
                "SELECT * FROM push_attestations WHERE push_id = :pushId ORDER BY timestamp DESC LIMIT 1",
                Map.of("pushId", pushId),
                AttestationRowMapper.INSTANCE);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // --- Utility ---

    private static MapSqlParameterSource pushRecordParams(PushRecord r) {
        return new MapSqlParameterSource()
                .addValue("id", r.getId())
                .addValue("timestamp", Timestamp.from(r.getTimestamp()))
                .addValue("url", r.getUrl())
                .addValue("upstreamUrl", r.getUpstreamUrl())
                .addValue("project", r.getProject())
                .addValue("repoName", r.getRepoName())
                .addValue("branch", r.getBranch())
                .addValue("commitFrom", r.getCommitFrom())
                .addValue("commitTo", r.getCommitTo())
                .addValue("message", r.getMessage())
                .addValue("author", r.getAuthor())
                .addValue("authorEmail", r.getAuthorEmail())
                .addValue("committer", r.getCommitter())
                .addValue("committerEmail", r.getCommitterEmail())
                .addValue("user", r.getUser())
                .addValue("userEmail", r.getUserEmail())
                .addValue("method", r.getMethod())
                .addValue("status", r.getStatus().name())
                .addValue("errorMessage", r.getErrorMessage())
                .addValue("blockedMessage", r.getBlockedMessage())
                .addValue("autoApproved", r.isAutoApproved())
                .addValue("autoRejected", r.isAutoRejected());
    }

    private String loadSchema() {
        try (var is = getClass().getResourceAsStream(SCHEMA_RESOURCE)) {
            if (is == null) {
                throw new RuntimeException("Schema resource not found: " + SCHEMA_RESOURCE);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load schema", e);
        }
    }
}
