package org.finos.gitproxy.git;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared context for collecting validation issues across multiple pre-receive hooks. Each validation hook writes issues
 * to this context without rejecting commands directly. A verifier hook at the end of the chain reads all collected
 * issues, reports them via sideband, and rejects if any are present.
 *
 * <p>This enables all validators to run on every push so the user sees all problems at once, rather than
 * fix-one-resubmit-fix-another.
 */
public class ValidationContext {

    private final List<ValidationIssue> issues = new ArrayList<>();

    /** Record an issue found by a validation hook. */
    public void addIssue(String hookName, String summary, String detail) {
        issues.add(new ValidationIssue(hookName, summary, detail));
    }

    /** Whether any validation hook reported an issue. */
    public boolean hasIssues() {
        return !issues.isEmpty();
    }

    /** All collected issues, in the order they were reported. */
    public List<ValidationIssue> getIssues() {
        return Collections.unmodifiableList(issues);
    }

    /** A single validation issue reported by a hook. */
    public record ValidationIssue(String hookName, String summary, String detail) {}
}
