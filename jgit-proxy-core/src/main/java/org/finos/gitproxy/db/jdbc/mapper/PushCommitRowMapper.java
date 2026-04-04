package org.finos.gitproxy.db.jdbc.mapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.finos.gitproxy.db.model.PushCommit;
import org.springframework.jdbc.core.RowMapper;

/** Maps a {@code push_commits} result-set row to a {@link PushCommit}. */
public final class PushCommitRowMapper implements RowMapper<PushCommit> {

    public static final PushCommitRowMapper INSTANCE = new PushCommitRowMapper();

    private PushCommitRowMapper() {}

    @Override
    public PushCommit mapRow(ResultSet rs, int rowNum) throws SQLException {
        String sobRaw = rs.getString("signed_off_by");
        List<String> signedOffBy =
                (sobRaw != null && !sobRaw.isBlank()) ? Arrays.asList(sobRaw.split("\n")) : new ArrayList<>();
        return PushCommit.builder()
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
                .signedOffBy(signedOffBy)
                .build();
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
