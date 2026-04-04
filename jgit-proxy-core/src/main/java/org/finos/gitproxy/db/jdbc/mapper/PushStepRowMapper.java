package org.finos.gitproxy.db.jdbc.mapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.finos.gitproxy.db.model.PushStep;
import org.finos.gitproxy.db.model.StepStatus;
import org.springframework.jdbc.core.RowMapper;

/** Maps a {@code push_steps} result-set row to a {@link PushStep}. */
public final class PushStepRowMapper implements RowMapper<PushStep> {

    public static final PushStepRowMapper INSTANCE = new PushStepRowMapper();

    private static final String LOG_SEPARATOR = "\n---LOG---\n";

    private PushStepRowMapper() {}

    @Override
    public PushStep mapRow(ResultSet rs, int rowNum) throws SQLException {
        return PushStep.builder()
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
                .build();
    }

    public static String joinLogs(List<String> logs) {
        if (logs == null || logs.isEmpty()) return null;
        return String.join(LOG_SEPARATOR, logs);
    }

    private static List<String> splitLogs(String logs) {
        if (logs == null || logs.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(logs.split(LOG_SEPARATOR)));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
