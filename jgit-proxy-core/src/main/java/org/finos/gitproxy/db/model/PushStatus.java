package org.finos.gitproxy.db.model;

/** Lifecycle status of a push through the proxy. */
public enum PushStatus {
    /** Push received but not yet processed. */
    RECEIVED,
    /** Push is being validated by hooks/filters. */
    PROCESSING,
    /** Push blocked by a validation hook, awaiting manual review. */
    BLOCKED,
    /** Push approved (manually or automatically). */
    APPROVED,
    /** Push rejected (manually or automatically). */
    REJECTED,
    /** Push successfully forwarded to upstream. */
    FORWARDED,
    /** Push canceled by a reviewer. */
    CANCELED,
    /** An error occurred during processing. */
    ERROR
}
