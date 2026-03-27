package org.finos.gitproxy.db.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/**
 * A single validation/processing step within a push. Captures what happened during each hook or filter execution.
 */
@Data
@Builder
public class PushStep {
    /** Unique identifier for this step. */
    @Builder.Default
    private String id = UUID.randomUUID().toString();

    /** ID of the parent push record. */
    private String pushId;

    /** Name of the hook or filter that ran (e.g., "AuthorEmailValidationHook"). */
    private String stepName;

    /** Execution order within the push pipeline. */
    private int stepOrder;

    /** Result status of this step. */
    @Builder.Default
    private StepStatus status = StepStatus.PASS;

    /** Structured content/result data (stored as JSON). */
    private String content;

    /** Error message if the step failed. */
    private String errorMessage;

    /** Message if the step caused a block. */
    private String blockedMessage;

    /** Log messages emitted during this step. */
    @Builder.Default
    private List<String> logs = new ArrayList<>();

    /** When this step was executed. */
    @Builder.Default
    private Instant timestamp = Instant.now();
}
