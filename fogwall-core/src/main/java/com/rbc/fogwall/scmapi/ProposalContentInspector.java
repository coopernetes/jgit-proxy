package com.rbc.fogwall.scmapi;

import com.rbc.fogwall.config.BlockConfig;
import com.rbc.fogwall.config.ContentPatternConfig;
import com.rbc.fogwall.config.SecretScanConfig;
import com.rbc.fogwall.validation.BlockedContentScanner;
import com.rbc.fogwall.validation.ContentPatternBundleResolver;
import com.rbc.fogwall.validation.ContentPatternFinding;
import com.rbc.fogwall.validation.PatternBundleScanner;
import com.rbc.fogwall.validation.SecretScanCheck;
import com.rbc.fogwall.validation.Violation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs fogwall's existing content rules over the prose a proposal carries, so a secret or blocked term cannot reach the
 * upstream through a pull request description when the same text would be blocked in a push.
 *
 * <p>Blocked literals and patterns come from {@code proposals.block}. Secret scanning reuses {@link SecretScanCheck}:
 * gitleaks reads stdin, and scans prose as readily as a diff. Content-pattern bundles reuse
 * {@link PatternBundleScanner}, the same structured PII/identifier detection the push path runs, so a national ID
 * number is caught in a merge request description as well as in a pushed line.
 *
 * <p><b>Fails closed</b> when secret scanning is enabled but the scanner cannot run, and a content-pattern match blocks
 * rather than warns: a forwarded proposal has already published its text upstream, and there is no reviewer to show a
 * warning to.
 */
@Slf4j
@RequiredArgsConstructor
public class ProposalContentInspector {

    private final Supplier<BlockConfig> blockConfig;
    private final Supplier<SecretScanConfig> secretScanConfig;
    private final SecretScanCheck secretScanCheck;
    private final Supplier<ContentPatternConfig> contentPatternConfig;

    /**
     * Everything wrong with this proposal's content; empty means it may be forwarded.
     *
     * <p>{@code fields} drives attribution — naming which field carried a match — while {@code payload} drives
     * coverage: the whole body, in both its raw and JSON-decoded readings, so neither an unenumerated field nor an
     * escaped one slips past. Findings are deduplicated by rule, since the same secret is present in both readings.
     */
    public List<String> inspect(List<ProposalContent> fields, ProposalPayload payload) {
        // Keyed by rule, not by summary: the field pass and the payload pass find the same match, and only the
        // first names a field. Running fields first means the attributed wording is the one kept.
        Map<String, String> byRule = new LinkedHashMap<>();
        var block = blockConfig.get();
        for (ProposalContent field : fields) {
            for (BlockedContentScanner.Match match : BlockedContentScanner.scan(field.text(), field.field(), block)) {
                byRule.putIfAbsent(match.rule(), match.summary());
            }
        }
        for (String reading : List.of(payload.raw(), payload.decoded())) {
            for (BlockedContentScanner.Match match : BlockedContentScanner.scan(reading, null, block)) {
                byRule.putIfAbsent(match.rule(), match.summary());
            }
        }
        var violations = new ArrayList<>(byRule.values());
        violations.addAll(scanForSecrets(payload));
        violations.addAll(scanForContentPatterns(payload));
        return List.copyOf(violations);
    }

    /**
     * Structured PII/identifier bundles over the same payload.
     *
     * <p>Reports the data type and jurisdiction only. The matched value is what the rule exists to keep out of the
     * upstream, and this violation is written to the audit record — repeating it there would move the number fogwall
     * just refused into fogwall's own database.
     */
    private List<String> scanForContentPatterns(ProposalPayload payload) {
        var config = contentPatternConfig.get();
        if (!config.isEnabled() || !config.isScanProposals()) {
            return List.of();
        }
        var bundles = ContentPatternBundleResolver.resolve(config);
        if (bundles.isEmpty()) {
            return List.of();
        }
        var scanner = new PatternBundleScanner(bundles);
        Set<String> violations = new LinkedHashSet<>();
        for (ContentPatternFinding finding : scanner.scan(payload.combined())) {
            violations.add("possible " + finding.dataType() + " (" + finding.jurisdiction()
                    + ") detected in proposal content");
        }
        return List.copyOf(violations);
    }

    /** One scanner invocation for the whole request — both readings together, not one call per field. */
    private List<String> scanForSecrets(ProposalPayload payload) {
        if (!secretScanConfig.get().isEnabled()) {
            return List.of();
        }
        Optional<List<Violation>> result = secretScanCheck.check(payload.combined());
        if (result.isEmpty()) {
            log.warn("Secret scanner unavailable — refusing proposal rather than forwarding it unscanned");
            return List.of("secret scanning is enabled but the scanner could not run");
        }
        Set<String> violations = new LinkedHashSet<>();
        for (Violation violation : result.get()) {
            violations.add("secret detected in proposal content: " + violation.reason());
        }
        return List.copyOf(violations);
    }
}
