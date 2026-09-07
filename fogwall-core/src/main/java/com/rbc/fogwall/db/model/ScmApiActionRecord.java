package com.rbc.fogwall.db.model;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/**
 * Audit record for a single SCM API proxy mutation — one per proxied mutation, never per read. Same auditability bar as
 * {@link PushRecord}: who, which rule, what target, what evidence.
 */
@Data
@Builder
public class ScmApiActionRecord {

    /** Unique identifier for this action. */
    @Builder.Default
    private String id = UUID.randomUUID().toString();

    /** When the action was received. */
    @Builder.Default
    private Instant timestamp = Instant.now();

    /** Provider name (e.g. "github"). */
    private String provider;

    /**
     * The caller's login on the provider (e.g. the GitHub username), carried by the same token resolution that produced
     * {@link #resolvedUser}. Holds what {@link PushRecord}'s {@code scmUsername} holds, and is named for it: a proxy
     * user may have several identities on one provider, so this is the one that acted. Null only for a cache entry
     * predating the login being stored alongside the proxy username.
     */
    private String scmUsername;

    /** Fogwall proxy username the caller resolved to. */
    private String resolvedUser;

    /** Repository owner the mutation's node ID resolved to. Null if resolution failed. */
    private String repoOwner;

    /** Repository name the mutation's node ID resolved to. Null if resolution failed. */
    private String repoName;

    /** Schema mutation field, e.g. "createIssue" — parsed from the GraphQL AST, never a client alias. */
    private String mutationField;

    /** The opaque node ID the mutation targeted. */
    private String nodeId;

    /** Which node type {@link #nodeId} refers to (Repository, Issue, PullRequest, ...). */
    private String nodeType;

    private ScmApiActionStatus status;

    /** Human-readable reason for a {@link ScmApiActionStatus#DENIED} or {@link ScmApiActionStatus#ERROR} outcome. */
    private String reason;

    /** Raw {@code variables} JSON from the mutation request, for post-hoc audit. */
    private String variablesJson;

    /**
     * Raw {@code User-Agent} the caller sent. Each SCM CLI advertises its version here, making this the anchor for
     * spotting a wire-format change after a CLI upgrade. Caller-controlled: evidence, never an access-control input.
     */
    private String userAgent;

    /** {@link #userAgent} classified — e.g. {@code GH_CLI}, {@code BROWSER}. */
    private String clientType;

    /**
     * The version {@link #clientType} advertised, parsed out of {@link #userAgent} best-effort. Null when the header
     * carried none fogwall could read — the raw value is kept either way. Queryable because the wire-format breaks this
     * trail exists to catch are version-specific: "which releases" is a question for a column, not a text scan.
     */
    private String clientVersion;
}
