package org.finos.gitproxy.db.jdbc.mapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import org.finos.gitproxy.db.model.Attestation;
import org.springframework.jdbc.core.RowMapper;

/** Maps a {@code push_attestations} result-set row to an {@link Attestation}. */
public final class AttestationRowMapper implements RowMapper<Attestation> {

    public static final AttestationRowMapper INSTANCE = new AttestationRowMapper();

    private AttestationRowMapper() {}

    @Override
    public Attestation mapRow(ResultSet rs, int rowNum) throws SQLException {
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

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
