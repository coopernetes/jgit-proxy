package com.rbc.fogwall.db.model;

/**
 * Outcome of a single SCM API proxy mutation.
 *
 * <p>{@link #DENIED} and {@link #ERROR} both mean the mutation never reached the upstream; they differ in whether
 * fogwall was able to decide. Keeping them apart is what makes filtering the audit trail for denials show real policy
 * violations, rather than burying them under malformed requests and upstream failures.
 */
public enum ScmApiActionStatus {
    /** The mutation cleared the allowlist and permission check and was relayed upstream. */
    FORWARDED,
    /**
     * fogwall evaluated a policy and the answer was no: the operation is not allowlisted, proposals are not enabled for
     * the provider, or the caller lacks the {@code PROPOSE} grant on the target repository.
     */
    DENIED,
    /**
     * The operation was permitted, but its content was not: a blocked term, pattern, or detected secret in the title,
     * description, or comment body. Distinct from {@link #DENIED} because the caller was entitled to perform the
     * operation — what they tried to publish through it is the problem.
     */
    REJECTED,
    /**
     * fogwall could not reach a decision and refused rather than guess: the request would not parse, no target could be
     * extracted from it, the target could not be resolved to a repository, or the upstream forward failed. Fails closed
     * exactly as {@link #DENIED} does — the distinction is about what the record means, not what the caller got.
     */
    ERROR
}
