package org.finos.gitproxy.db.jdbc.mapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import org.finos.gitproxy.db.model.PushRecord;
import org.finos.gitproxy.db.model.PushStatus;
import org.springframework.jdbc.core.RowMapper;

/** Maps a {@code push_records} result-set row to a {@link PushRecord}. */
public final class PushRecordRowMapper implements RowMapper<PushRecord> {

    public static final PushRecordRowMapper INSTANCE = new PushRecordRowMapper();

    private PushRecordRowMapper() {}

    @Override
    public PushRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return PushRecord.builder()
                .id(rs.getString("id"))
                .timestamp(toInstant(rs.getTimestamp("timestamp")))
                .url(rs.getString("url"))
                .upstreamUrl(rs.getString("upstream_url"))
                .provider(rs.getString("provider"))
                .project(rs.getString("project"))
                .repoName(rs.getString("repo_name"))
                .branch(rs.getString("branch"))
                .commitFrom(rs.getString("commit_from"))
                .commitTo(rs.getString("commit_to"))
                .message(rs.getString("message"))
                .author(rs.getString("author"))
                .authorEmail(rs.getString("author_email"))
                .committer(rs.getString("committer"))
                .committerEmail(rs.getString("committer_email"))
                .user(rs.getString("push_user"))
                .resolvedUser(rs.getString("resolved_user"))
                .scmUsername(rs.getString("scm_username"))
                .userEmail(rs.getString("user_email"))
                .method(rs.getString("method"))
                .status(PushStatus.valueOf(rs.getString("status")))
                .errorMessage(rs.getString("error_message"))
                .blockedMessage(rs.getString("blocked_message"))
                .autoApproved(rs.getBoolean("auto_approved"))
                .autoRejected(rs.getBoolean("auto_rejected"))
                .forwardedAt(toInstant(rs.getTimestamp("forwarded_at")))
                .build();
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
