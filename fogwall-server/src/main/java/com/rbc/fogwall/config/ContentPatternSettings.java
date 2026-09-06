package com.rbc.fogwall.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Binds the {@code content-patterns:} block in fogwall.yml. Selects fogwall's built-in PII/identifier pattern bundles
 * (SIN, SSN, NINO, etc.) to scan pushed diffs, commit messages and proposal prose against. WARN-only on the push path;
 * blocking on the proposal path, which has no reviewer to show a warning to - see {@link ContentPatternConfig}.
 *
 * <p>Defaults to fully inert (disabled, no bundles selected) - same posture as {@link BinaryBlobSettings}. The shipped
 * {@code fogwall.yml} explicitly enables this with recommended bundles; this class's own defaults are the safe fallback
 * for configs that omit the {@code content-patterns:} key entirely.
 */
@Data
public class ContentPatternSettings {
    private boolean enabled = false;
    private List<String> bundles = new ArrayList<>();
    private boolean scanDiff = true;
    private boolean scanCommitMessages = true;
    private boolean scanProposals = true;
}
