package org.finos.gitproxy.db.model;

import java.time.Instant;
import lombok.Builder;
import lombok.Data;

/** An approval, rejection, or cancellation attestation for a push. */
@Data
@Builder
public class Attestation {

    public enum Type {
        APPROVAL,
        REJECTION,
        CANCELLATION
    }

    /** ID of the parent push record. */
    private String pushId;

    /** Type of attestation. */
    private Type type;

    /** Username of the reviewer. */
    private String reviewerUsername;

    /** Email of the reviewer. */
    private String reviewerEmail;

    /** Reason for the decision (required for rejections). */
    private String reason;

    /** Whether this was an automated decision. */
    @Builder.Default
    private boolean automated = false;

    /** When the attestation was made. */
    @Builder.Default
    private Instant timestamp = Instant.now();
}
