package org.finos.gitproxy.db.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/** A commit included in a push. Stored alongside the PushRecord for audit trail. */
@Data
@Builder
public class PushCommit {
    /** ID of the parent push record. */
    private String pushId;

    /** Commit SHA. */
    private String sha;

    /** Parent commit SHA. */
    private String parentSha;

    /** Author name. */
    private String authorName;

    /** Author email. */
    private String authorEmail;

    /** Committer name. */
    private String committerName;

    /** Committer email. */
    private String committerEmail;

    /** Commit message. */
    private String message;

    /** When the commit was authored. */
    private Instant commitDate;

    /** GPG/SSH signature if present. */
    private String signature;

    /** {@code Signed-off-by:} trailers extracted from the commit message, in order. */
    @Builder.Default
    private List<String> signedOffBy = new ArrayList<>();
}
