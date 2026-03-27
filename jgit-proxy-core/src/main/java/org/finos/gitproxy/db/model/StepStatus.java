package org.finos.gitproxy.db.model;

/** Result status of a single validation step. */
public enum StepStatus {
    PASS,
    FAIL,
    BLOCKED,
    SKIPPED
}
