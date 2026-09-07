package com.rbc.fogwall.servlet;

import com.rbc.fogwall.db.model.ScmApiActionStatus;
import com.rbc.fogwall.scmapi.ScmApiClientType;
import lombok.Data;

/**
 * Per-request state shared across the SCM API proxy filter chain, attached under {@link #SCM_API_REQUEST_ATTR} — the
 * SCM API counterpart to {@code GitRequestDetails}, shared by every dialect.
 *
 * <p>{@link #mutationField} is {@code null} for a pure read (a GraphQL {@code query} document, or a REST {@code GET}) —
 * the audit filter only writes a {@link com.rbc.fogwall.db.model.ScmApiActionRecord} when it is set, since reads are
 * not audited individually.
 */
@Data
public class ScmApiRequestContext {

    /**
     * How much of a proposal request body fogwall will read.
     *
     * <p>Generous for the traffic it serves — GitHub caps an issue body at 65,536 characters and GitLab a description
     * at 1 MB — and it bounds what content inspection costs, since the scanner runs over the whole payload and a
     * request with no ceiling gives it none either.
     */
    public static final long MAX_BODY_BYTES = 4L * 1024 * 1024;

    /** Request attribute holding the {@link ScmApiRequestContext} for the current request. */
    public static final String SCM_API_REQUEST_ATTR = "com.rbc.fogwall.scmapi.context";

    private String provider;

    /** SCM login the caller's token resolved to, before proxy-user resolution. Set by the authenticate filter. */
    private String scmLogin;

    /** Fogwall proxy username. Set by the authenticate filter once identity resolution succeeds. */
    private String resolvedUser;

    /** Schema mutation field, e.g. "createIssue". {@code null} for a pure read. */
    private String mutationField;

    private String nodeId;
    private String nodeType;
    private String repoOwner;
    private String repoName;
    private String variablesJson;

    /**
     * Raw {@code User-Agent} as sent. Audited because every SCM CLI advertises its version, which is the anchor for
     * spotting a wire-format change after a CLI upgrade. Caller-controlled, so never an input to an access decision.
     */
    private String userAgent;

    /** {@link #userAgent} classified. Recorded for audit; only ever used to refuse a client type, never to permit. */
    private ScmApiClientType clientType;

    /** The version {@link #clientType} advertises, parsed best-effort. Null when the header carries none to read. */
    private String clientVersion;

    /**
     * Final outcome, set once known: {@code DENIED}/{@code ERROR} by the gate filter before it short-circuits the
     * chain, or {@code FORWARDED}/{@code ERROR} by the forwarding servlet once the upstream response is known. The
     * audit filter reads this in its {@code finally} block, after the chain has fully unwound either way.
     */
    private ScmApiActionStatus status;

    private String reason;
}
