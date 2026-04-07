package org.finos.gitproxy.db.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/**
 * Represents a single push operation through the proxy. This is the primary audit record, equivalent to the Action
 * class in git-proxy (Node.js).
 */
@Data
@Builder
public class PushRecord {
    /** Unique identifier for this push. */
    @Builder.Default
    private String id = UUID.randomUUID().toString();

    /** When the push was received. */
    @Builder.Default
    private Instant timestamp = Instant.now();

    /** Full repository URL being pushed to. */
    private String url;

    /** The upstream repository URL being proxied to (e.g., https://github.com/owner/repo). */
    private String upstreamUrl;

    /** Provider name (e.g., "github", "gitlab"). */
    private String provider;

    /** Project/organization name (e.g., "finos"). */
    private String project;

    /** Repository name (e.g., "git-proxy"). */
    private String repoName;

    /** Target branch (e.g., "refs/heads/main"). */
    private String branch;

    /** Starting commit SHA (old ref, the push range start). */
    private String commitFrom;

    /** Ending commit SHA (new ref, the push range end). */
    private String commitTo;

    /** First line of the head commit message, for quick display. */
    private String message;

    /** Git commit author name. */
    private String author;

    /** Git commit author email. */
    private String authorEmail;

    /** Git committer name (who last applied the commit - may differ from author). */
    private String committer;

    /** Git committer email. */
    private String committerEmail;

    /** Authenticated user performing the push. */
    private String user;

    /**
     * SCM identity resolved for this push (proxy username after successful identity resolution). Null if resolution
     * failed.
     */
    private String resolvedUser;

    /**
     * The actual SCM username on the upstream provider (e.g. GitHub login "coopernetes"). Populated when token-based
     * identity resolution succeeds; null for config-only resolution or open mode. Use this — not {@link #user} — for
     * building provider profile links.
     */
    private String scmUsername;

    /** Authenticated user's email. Populated when user management is available; null until then. */
    private String userEmail;

    /** HTTP method used. */
    private String method;

    /** Current lifecycle status. */
    @Builder.Default
    private PushStatus status = PushStatus.RECEIVED;

    /** Error message if status is ERROR. */
    private String errorMessage;

    /** Message explaining why the push is blocked. */
    private String blockedMessage;

    /** Whether the push was auto-approved by policy. */
    @Builder.Default
    private boolean autoApproved = false;

    /** Whether the push was auto-rejected by policy. */
    @Builder.Default
    private boolean autoRejected = false;

    /** When the push was forwarded upstream. Null until status reaches FORWARDED. */
    private Instant forwardedAt;

    /** Validation steps executed for this push. */
    @Builder.Default
    private List<PushStep> steps = new ArrayList<>();

    /** Commits included in this push. */
    @Builder.Default
    private List<PushCommit> commits = new ArrayList<>();

    /** Attestation record if the push was manually reviewed. */
    private Attestation attestation;
}
