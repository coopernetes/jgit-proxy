package org.finos.gitproxy.db.jdbc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDBC-based {@link PushStore} implementation. Works with H2 (in-memory and file), SQLite, and PostgreSQL.
 *
 * <p>Uses plain JDBC with no ORM framework, keeping it usable from both the Jetty and Spring modules.
 */
public class JdbcPushStore implements PushStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcPushStore.class);
    private static final String SCHEMA_RESOURCE = "/db/schema.sql";
    private static final String LOG_SEPARATOR = "\n---LOG---\n";

    private final DataSource dataSource;

    public JdbcPushStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void initialize() {
        String schema = loadSchema();
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            for (String sql : schema.split(";")) {
                String trimmed = sql.trim();
                if (!trimmed.isEmpty()) {
                    stmt.execute(trimmed);
                }
            }
            log.info("Push store schema initialized");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize push store schema", e);
        }
    }

    @Override
    public void save(PushRecord record) {
        String insertSql =
                """
                INSERT INTO push_records (id, timestamp, url, upstream_url, project, repo_name, branch,
                    commit_from, commit_to, author, author_email, push_user, method, status,
                    error_message, blocked_message, auto_approved, auto_rejected)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    bindPushRecord(ps, record);
                    ps.executeUpdate();
                }

                // Save steps
                saveSteps(conn, record.getId(), record.getSteps());

                // Save commits
                saveCommits(conn, record.getId(), record.getCommits());

                // Save attestation
                if (record.getAttestation() != null) {
                    saveAttestation(conn, record.getId(), record.getAttestation());
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save push record: " + record.getId(), e);
        }
    }

    @Override
    public Optional<PushRecord> findById(String id) {
        try (Connection conn = dataSource.getConnection()) {
            PushRecord record = loadRecord(conn, id);
            return Optional.ofNullable(record);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find push record: " + id, e);
        }
    }

    @Override
    public List<PushRecord> find(PushQuery query) {
        StringBuilder sql = new StringBuilder("SELECT * FROM push_records WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (query.getStatus() != null) {
            sql.append(" AND status = ?");
            params.add(query.getStatus().name());
        }
        if (query.getProject() != null) {
            sql.append(" AND project = ?");
            params.add(query.getProject());
        }
        if (query.getRepoName() != null) {
            sql.append(" AND repo_name = ?");
            params.add(query.getRepoName());
        }
        if (query.getBranch() != null) {
            sql.append(" AND branch = ?");
            params.add(query.getBranch());
        }
        if (query.getUser() != null) {
            sql.append(" AND push_user = ?");
            params.add(query.getUser());
        }
        if (query.getAuthorEmail() != null) {
            sql.append(" AND author_email = ?");
            params.add(query.getAuthorEmail());
        }

        sql.append(" ORDER BY timestamp ");
        sql.append(query.isNewestFirst() ? "DESC" : "ASC");
        sql.append(" LIMIT ?");
        params.add(query.getLimit());

        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }

            List<PushRecord> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    PushRecord record = mapRecord(rs);
                    record.setSteps(loadSteps(conn, id));
                    record.setCommits(loadCommits(conn, id));
                    record.setAttestation(loadAttestation(conn, id));
                    results.add(record);
                }
            }
            return results;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query push records", e);
        }
    }

    @Override
    public void delete(String id) {
        // CASCADE will handle child tables
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM push_records WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete push record: " + id, e);
        }
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
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps =
                        conn.prepareStatement("UPDATE push_records SET status = ? WHERE id = ?")) {
                    ps.setString(1, status.name());
                    ps.setString(2, id);
                    int updated = ps.executeUpdate();
                    if (updated == 0) {
                        throw new IllegalArgumentException("Push not found: " + id);
                    }
                }
                if (attestation != null) {
                    saveAttestation(conn, id, attestation);
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update push status: " + id, e);
        }
        return findById(id).orElseThrow();
    }

    private PushRecord loadRecord(Connection conn, String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM push_records WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                PushRecord record = mapRecord(rs);
                record.setSteps(loadSteps(conn, id));
                record.setCommits(loadCommits(conn, id));
                record.setAttestation(loadAttestation(conn, id));
                return record;
            }
        }
    }

    private PushRecord mapRecord(ResultSet rs) throws SQLException {
        return PushRecord.builder()
                .id(rs.getString("id"))
                .timestamp(toInstant(rs.getTimestamp("timestamp")))
                .url(rs.getString("url"))
                .upstreamUrl(rs.getString("upstream_url"))
                .project(rs.getString("project"))
                .repoName(rs.getString("repo_name"))
                .branch(rs.getString("branch"))
                .commitFrom(rs.getString("commit_from"))
                .commitTo(rs.getString("commit_to"))
                .author(rs.getString("author"))
                .authorEmail(rs.getString("author_email"))
                .user(rs.getString("push_user"))
                .method(rs.getString("method"))
                .status(PushStatus.valueOf(rs.getString("status")))
                .errorMessage(rs.getString("error_message"))
                .blockedMessage(rs.getString("blocked_message"))
                .autoApproved(rs.getBoolean("auto_approved"))
                .autoRejected(rs.getBoolean("auto_rejected"))
                .build();
    }

    private void bindPushRecord(PreparedStatement ps, PushRecord r) throws SQLException {
        ps.setString(1, r.getId());
        ps.setTimestamp(2, Timestamp.from(r.getTimestamp()));
        ps.setString(3, r.getUrl());
        ps.setString(4, r.getUpstreamUrl());
        ps.setString(5, r.getProject());
        ps.setString(6, r.getRepoName());
        ps.setString(7, r.getBranch());
        ps.setString(8, r.getCommitFrom());
        ps.setString(9, r.getCommitTo());
        ps.setString(10, r.getAuthor());
        ps.setString(11, r.getAuthorEmail());
        ps.setString(12, r.getUser());
        ps.setString(13, r.getMethod());
        ps.setString(14, r.getStatus().name());
        ps.setString(15, r.getErrorMessage());
        ps.setString(16, r.getBlockedMessage());
        ps.setBoolean(17, r.isAutoApproved());
        ps.setBoolean(18, r.isAutoRejected());
    }

    // --- Steps ---

    private void saveSteps(Connection conn, String pushId, List<PushStep> steps) throws SQLException {
        if (steps == null || steps.isEmpty()) return;
        String sql =
                """
                INSERT INTO push_steps (id, push_id, step_name, step_order, status, content,
                    error_message, blocked_message, logs, timestamp)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (PushStep step : steps) {
                ps.setString(1, step.getId());
                ps.setString(2, pushId);
                ps.setString(3, step.getStepName());
                ps.setInt(4, step.getStepOrder());
                ps.setString(5, step.getStatus().name());
                ps.setString(6, step.getContent());
                ps.setString(7, step.getErrorMessage());
                ps.setString(8, step.getBlockedMessage());
                ps.setString(9, joinLogs(step.getLogs()));
                ps.setTimestamp(10, Timestamp.from(step.getTimestamp()));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private List<PushStep> loadSteps(Connection conn, String pushId) throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement("SELECT * FROM push_steps WHERE push_id = ? ORDER BY step_order")) {
            ps.setString(1, pushId);
            List<PushStep> steps = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    steps.add(PushStep.builder()
                            .id(rs.getString("id"))
                            .pushId(rs.getString("push_id"))
                            .stepName(rs.getString("step_name"))
                            .stepOrder(rs.getInt("step_order"))
                            .status(StepStatus.valueOf(rs.getString("status")))
                            .content(rs.getString("content"))
                            .errorMessage(rs.getString("error_message"))
                            .blockedMessage(rs.getString("blocked_message"))
                            .logs(splitLogs(rs.getString("logs")))
                            .timestamp(toInstant(rs.getTimestamp("timestamp")))
                            .build());
                }
            }
            return steps;
        }
    }

    // --- Commits ---

    private void saveCommits(Connection conn, String pushId, List<PushCommit> commits) throws SQLException {
        if (commits == null || commits.isEmpty()) return;
        String sql =
                """
                INSERT INTO push_commits (push_id, sha, parent_sha, author_name, author_email,
                    committer_name, committer_email, message, commit_date, signature)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (PushCommit c : commits) {
                ps.setString(1, pushId);
                ps.setString(2, c.getSha());
                ps.setString(3, c.getParentSha());
                ps.setString(4, c.getAuthorName());
                ps.setString(5, c.getAuthorEmail());
                ps.setString(6, c.getCommitterName());
                ps.setString(7, c.getCommitterEmail());
                ps.setString(8, c.getMessage());
                ps.setTimestamp(9, c.getCommitDate() != null ? Timestamp.from(c.getCommitDate()) : null);
                ps.setString(10, c.getSignature());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private List<PushCommit> loadCommits(Connection conn, String pushId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM push_commits WHERE push_id = ?")) {
            ps.setString(1, pushId);
            List<PushCommit> commits = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    commits.add(PushCommit.builder()
                            .pushId(rs.getString("push_id"))
                            .sha(rs.getString("sha"))
                            .parentSha(rs.getString("parent_sha"))
                            .authorName(rs.getString("author_name"))
                            .authorEmail(rs.getString("author_email"))
                            .committerName(rs.getString("committer_name"))
                            .committerEmail(rs.getString("committer_email"))
                            .message(rs.getString("message"))
                            .commitDate(toInstant(rs.getTimestamp("commit_date")))
                            .signature(rs.getString("signature"))
                            .build());
                }
            }
            return commits;
        }
    }

    // --- Attestation ---

    private void saveAttestation(Connection conn, String pushId, Attestation att) throws SQLException {
        String sql =
                """
                INSERT INTO push_attestations (push_id, type, reviewer_username, reviewer_email,
                    reason, automated, timestamp)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pushId);
            ps.setString(2, att.getType().name());
            ps.setString(3, att.getReviewerUsername());
            ps.setString(4, att.getReviewerEmail());
            ps.setString(5, att.getReason());
            ps.setBoolean(6, att.isAutomated());
            ps.setTimestamp(7, Timestamp.from(att.getTimestamp()));
            ps.executeUpdate();
        }
    }

    private Attestation loadAttestation(Connection conn, String pushId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM push_attestations WHERE push_id = ? ORDER BY timestamp DESC LIMIT 1")) {
            ps.setString(1, pushId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return Attestation.builder()
                        .pushId(rs.getString("push_id"))
                        .type(Attestation.Type.valueOf(rs.getString("type")))
                        .reviewerUsername(rs.getString("reviewer_username"))
                        .reviewerEmail(rs.getString("reviewer_email"))
                        .reason(rs.getString("reason"))
                        .automated(rs.getBoolean("automated"))
                        .timestamp(toInstant(rs.getTimestamp("timestamp")))
                        .build();
            }
        }
    }

    // --- Utility ---

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    private static String joinLogs(List<String> logs) {
        if (logs == null || logs.isEmpty()) return null;
        return String.join(LOG_SEPARATOR, logs);
    }

    private static List<String> splitLogs(String logs) {
        if (logs == null || logs.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(logs.split(LOG_SEPARATOR)));
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
