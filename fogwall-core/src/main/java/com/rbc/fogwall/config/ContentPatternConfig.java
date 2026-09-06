package com.rbc.fogwall.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * Runtime configuration for content-pattern scanning against fogwall's built-in pattern bundles (structured
 * PII/identifier detection - SIN, SSN, NINO, etc. - distinct from credential-shaped secret scanning). Scans diff
 * content, commit messages, and the prose a proposal carries. Operator-authored custom patterns are out of scope here -
 * that's already covered by {@code DiffScanConfig}'s {@code block.literals}/{@code block.patterns}.
 *
 * <p><b>On the push path, WARN-only.</b> There is no {@code mode}/ENFORCE option - a match never blocks a push, it's
 * recorded as a {@code WARN} step so the mandatory human reviewer sees it. Blocking on these patterns with no override
 * path for a false positive would be worse than the visibility gap this closes; ENFORCE support is intentionally left
 * out until a policy exception model exists to pair with it.
 *
 * <p><b>On the proposal path, blocking.</b> That WARN rests on a reviewer downstream who sees it, and a proposal has
 * none - it is forwarded or refused, with no held state to annotate. A warning recorded against text already published
 * upstream is not a control, so a match there refuses the proposal, the same as a blocked term or a detected secret.
 *
 * <p>Bundles are selected by name via {@code bundles} (see {@code BuiltInPatternBundleSource} for what's shipped) -
 * nothing is scanned unless both {@code enabled} is true and at least one bundle is listed.
 *
 * <p>{@code scanDiff}/{@code scanCommitMessages}/{@code scanProposals} independently gate the three content sources -
 * an operator who considers commit messages low-risk (or wants to reduce push-summary noise) can disable that half
 * without affecting diff scanning, and vice versa. All default {@code true}, so enabling a bundle covers every surface
 * it can appear on.
 */
@Data
@Builder
public class ContentPatternConfig {

    @Builder.Default
    private boolean enabled = false;

    /**
     * Names of built-in bundles to scan with, e.g. {@code national-id-ca}, {@code national-id-us},
     * {@code national-id-gb}.
     */
    @Builder.Default
    private List<String> bundles = new ArrayList<>();

    @Builder.Default
    private boolean scanDiff = true;

    @Builder.Default
    private boolean scanCommitMessages = true;

    @Builder.Default
    private boolean scanProposals = true;

    public static ContentPatternConfig defaultConfig() {
        return ContentPatternConfig.builder().build();
    }
}
