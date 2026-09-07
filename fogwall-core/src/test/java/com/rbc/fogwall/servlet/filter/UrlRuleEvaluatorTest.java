package com.rbc.fogwall.servlet.filter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rbc.fogwall.db.UrlRuleRegistry;
import com.rbc.fogwall.db.memory.InMemoryUrlRuleRegistry;
import com.rbc.fogwall.db.model.AccessRule;
import com.rbc.fogwall.db.model.MatchTarget;
import com.rbc.fogwall.db.model.MatchType;
import com.rbc.fogwall.git.HttpOperation;
import com.rbc.fogwall.provider.FogwallProvider;
import com.rbc.fogwall.provider.GitHubProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UrlRuleEvaluator}. Each test exercises a specific branch of the evaluation algorithm without
 * any Servlet or JGit machinery, confirming that the same logic applies to both proxy-mode and server mode.
 */
class UrlRuleEvaluatorTest {

    private static final FogwallProvider GITHUB = new GitHubProvider("/proxy");

    private static UrlRuleEvaluator evaluatorWith(AccessRule... rules) {
        var registry = new InMemoryUrlRuleRegistry();
        for (AccessRule r : rules) registry.save(r);
        return new UrlRuleEvaluator(registry, GITHUB);
    }

    private static AccessRule allow(MatchTarget target, String value) {
        return AccessRule.builder()
                .ruleOrder(100)
                .access(AccessRule.Access.ALLOW)
                .operation(AccessRule.Operation.BOTH)
                .target(target)
                .value(value)
                .matchType(MatchType.GLOB)
                .build();
    }

    private static AccessRule deny(MatchTarget target, String value) {
        return AccessRule.builder()
                .ruleOrder(100)
                .access(AccessRule.Access.DENY)
                .operation(AccessRule.Operation.BOTH)
                .target(target)
                .value(value)
                .matchType(MatchType.GLOB)
                .build();
    }

    // ── No matching rules — fail-closed ──────────────────────────────────────

    @Test
    void noRegistry_notAllowed() {
        var evaluator = new UrlRuleEvaluator(null, null);
        assertInstanceOf(
                UrlRuleEvaluator.Result.NotAllowed.class,
                evaluator.evaluate("org/repo", "org", "repo", HttpOperation.PUSH));
    }

    @Test
    void emptyRegistry_notAllowed() {
        var evaluator = new UrlRuleEvaluator(new InMemoryUrlRuleRegistry(), GITHUB);
        assertInstanceOf(
                UrlRuleEvaluator.Result.NotAllowed.class,
                evaluator.evaluate("org/repo", "org", "repo", HttpOperation.FETCH));
    }

    // ── Allow rules ───────────────────────────────────────────────────────────

    @Test
    void allowRule_ownerMatch_allowed() {
        var evaluator = evaluatorWith(allow(MatchTarget.OWNER, "myorg"));
        assertInstanceOf(
                UrlRuleEvaluator.Result.Allowed.class,
                evaluator.evaluate("myorg/repo", "myorg", "repo", HttpOperation.PUSH));
    }

    @Test
    void allowRule_slugMatch_allowed() {
        var rule = AccessRule.builder()
                .ruleOrder(100)
                .access(AccessRule.Access.ALLOW)
                .operation(AccessRule.Operation.BOTH)
                .target(MatchTarget.SLUG)
                .value("/myorg/repo")
                .matchType(MatchType.LITERAL)
                .build();
        var evaluator = evaluatorWith(rule);
        assertInstanceOf(
                UrlRuleEvaluator.Result.Allowed.class,
                evaluator.evaluate("/myorg/repo", "myorg", "repo", HttpOperation.PUSH));
    }

    @Test
    void allowRule_nameGlob_allowed() {
        var rule = AccessRule.builder()
                .ruleOrder(100)
                .access(AccessRule.Access.ALLOW)
                .operation(AccessRule.Operation.BOTH)
                .target(MatchTarget.NAME)
                .value("feature-*")
                .matchType(MatchType.GLOB)
                .build();
        var evaluator = evaluatorWith(rule);
        assertInstanceOf(
                UrlRuleEvaluator.Result.Allowed.class,
                evaluator.evaluate("org/feature-abc", "org", "feature-abc", HttpOperation.PUSH));
        assertInstanceOf(
                UrlRuleEvaluator.Result.NotAllowed.class,
                evaluator.evaluate("org/main-branch", "org", "main-branch", HttpOperation.PUSH));
    }

    @Test
    void allowRule_noMatch_notAllowed() {
        var evaluator = evaluatorWith(allow(MatchTarget.OWNER, "myorg"));
        assertInstanceOf(
                UrlRuleEvaluator.Result.NotAllowed.class,
                evaluator.evaluate("otherorg/repo", "otherorg", "repo", HttpOperation.PUSH));
    }

    // ── Deny rules ────────────────────────────────────────────────────────────

    @Test
    void denyRule_lowerOrderBeatsAllowRule_denied() {
        var denyRule = AccessRule.builder()
                .ruleOrder(100)
                .access(AccessRule.Access.DENY)
                .operation(AccessRule.Operation.BOTH)
                .target(MatchTarget.OWNER)
                .value("blocked")
                .matchType(MatchType.GLOB)
                .build();
        var allowRule = AccessRule.builder()
                .ruleOrder(200)
                .access(AccessRule.Access.ALLOW)
                .operation(AccessRule.Operation.BOTH)
                .target(MatchTarget.OWNER)
                .value("blocked")
                .matchType(MatchType.GLOB)
                .build();
        var evaluator = evaluatorWith(denyRule, allowRule);
        assertInstanceOf(
                UrlRuleEvaluator.Result.Denied.class,
                evaluator.evaluate("blocked/repo", "blocked", "repo", HttpOperation.PUSH));
    }

    @Test
    void allowRule_lowerOrderBeatsDenyRule_allowed() {
        var allowRule = AccessRule.builder()
                .ruleOrder(100)
                .access(AccessRule.Access.ALLOW)
                .operation(AccessRule.Operation.BOTH)
                .target(MatchTarget.OWNER)
                .value("myorg")
                .matchType(MatchType.GLOB)
                .build();
        var denyRule = AccessRule.builder()
                .ruleOrder(200)
                .access(AccessRule.Access.DENY)
                .operation(AccessRule.Operation.BOTH)
                .target(MatchTarget.OWNER)
                .value("myorg")
                .matchType(MatchType.GLOB)
                .build();
        var evaluator = evaluatorWith(allowRule, denyRule);
        assertInstanceOf(
                UrlRuleEvaluator.Result.Allowed.class,
                evaluator.evaluate("myorg/repo", "myorg", "repo", HttpOperation.PUSH));
    }

    @Test
    void denyRule_noMatch_allowRuleChecked() {
        var evaluator = evaluatorWith(deny(MatchTarget.OWNER, "blocked"), allow(MatchTarget.OWNER, "allowed"));
        assertInstanceOf(
                UrlRuleEvaluator.Result.Allowed.class,
                evaluator.evaluate("allowed/repo", "allowed", "repo", HttpOperation.PUSH));
    }

    // ── Operations filtering ──────────────────────────────────────────────────

    @Test
    void fetchOnlyAllowRule_doesNotEngageForPush() {
        var rule = AccessRule.builder()
                .ruleOrder(100)
                .access(AccessRule.Access.ALLOW)
                .operation(AccessRule.Operation.FETCH)
                .target(MatchTarget.OWNER)
                .value("myorg")
                .matchType(MatchType.GLOB)
                .build();
        var evaluator = evaluatorWith(rule);
        assertInstanceOf(
                UrlRuleEvaluator.Result.NotAllowed.class,
                evaluator.evaluate("myorg/repo", "myorg", "repo", HttpOperation.PUSH),
                "FETCH-only allow rule must not engage for PUSH");
    }

    @Test
    void pushOnlyAllowRule_doesNotEngageForFetch() {
        var rule = AccessRule.builder()
                .ruleOrder(100)
                .access(AccessRule.Access.ALLOW)
                .operation(AccessRule.Operation.PUSH)
                .target(MatchTarget.OWNER)
                .value("myorg")
                .matchType(MatchType.GLOB)
                .build();
        var evaluator = evaluatorWith(rule);
        assertInstanceOf(
                UrlRuleEvaluator.Result.NotAllowed.class,
                evaluator.evaluate("myorg/repo", "myorg", "repo", HttpOperation.FETCH),
                "PUSH-only allow rule must not engage for FETCH");
    }

    @Test
    void fetchOnlyDenyRule_doesNotBlockPush() {
        var fetchDeny = AccessRule.builder()
                .ruleOrder(100)
                .access(AccessRule.Access.DENY)
                .operation(AccessRule.Operation.FETCH)
                .target(MatchTarget.OWNER)
                .value("myorg")
                .matchType(MatchType.GLOB)
                .build();
        var pushAllow = AccessRule.builder()
                .ruleOrder(200)
                .access(AccessRule.Access.ALLOW)
                .operation(AccessRule.Operation.BOTH)
                .target(MatchTarget.OWNER)
                .value("myorg")
                .matchType(MatchType.GLOB)
                .build();
        var evaluator = evaluatorWith(fetchDeny, pushAllow);
        assertInstanceOf(
                UrlRuleEvaluator.Result.Allowed.class,
                evaluator.evaluate("myorg/repo", "myorg", "repo", HttpOperation.PUSH),
                "FETCH-only deny rule must not block a push");
    }

    @Test
    void pushOnlyDenyRule_doesNotBlockFetch() {
        var pushDeny = AccessRule.builder()
                .ruleOrder(100)
                .access(AccessRule.Access.DENY)
                .operation(AccessRule.Operation.PUSH)
                .target(MatchTarget.OWNER)
                .value("myorg")
                .matchType(MatchType.GLOB)
                .build();
        var fetchAllow = AccessRule.builder()
                .ruleOrder(200)
                .access(AccessRule.Access.ALLOW)
                .operation(AccessRule.Operation.BOTH)
                .target(MatchTarget.OWNER)
                .value("myorg")
                .matchType(MatchType.GLOB)
                .build();
        var evaluator = evaluatorWith(pushDeny, fetchAllow);
        assertInstanceOf(
                UrlRuleEvaluator.Result.Allowed.class,
                evaluator.evaluate("myorg/repo", "myorg", "repo", HttpOperation.FETCH),
                "PUSH-only deny rule must not block a fetch");
    }

    // ── evaluateTrail ─────────────────────────────────────────────────────────

    @Test
    void evaluateTrail_recordsAllRulesInOrderWithMatchFlag() {
        var denyRule = AccessRule.builder()
                .ruleOrder(100)
                .access(AccessRule.Access.DENY)
                .operation(AccessRule.Operation.BOTH)
                .target(MatchTarget.OWNER)
                .value("blocked")
                .matchType(MatchType.GLOB)
                .build();
        var allowRule = AccessRule.builder()
                .ruleOrder(200)
                .access(AccessRule.Access.ALLOW)
                .operation(AccessRule.Operation.BOTH)
                .target(MatchTarget.OWNER)
                .value("myorg")
                .matchType(MatchType.GLOB)
                .build();
        var evaluator = evaluatorWith(denyRule, allowRule);

        var trail = evaluator.evaluateTrail("myorg/repo", "myorg", "repo", HttpOperation.PUSH);

        assertEquals(2, trail.steps().size());
        assertEquals(denyRule.getId(), trail.steps().get(0).rule().getId());
        assertFalse(trail.steps().get(0).matched(), "deny rule for 'blocked' must not match 'myorg'");
        assertEquals(allowRule.getId(), trail.steps().get(1).rule().getId());
        assertTrue(trail.steps().get(1).matched());
        assertInstanceOf(UrlRuleEvaluator.Result.Allowed.class, trail.result());
    }

    @Test
    void evaluateTrail_firstMatchWinsButLaterRulesStillRecorded() {
        var denyRule = AccessRule.builder()
                .ruleOrder(100)
                .access(AccessRule.Access.DENY)
                .operation(AccessRule.Operation.BOTH)
                .target(MatchTarget.OWNER)
                .value("myorg")
                .matchType(MatchType.GLOB)
                .build();
        var allowRule = AccessRule.builder()
                .ruleOrder(200)
                .access(AccessRule.Access.ALLOW)
                .operation(AccessRule.Operation.BOTH)
                .target(MatchTarget.OWNER)
                .value("myorg")
                .matchType(MatchType.GLOB)
                .build();
        var evaluator = evaluatorWith(denyRule, allowRule);

        var trail = evaluator.evaluateTrail("myorg/repo", "myorg", "repo", HttpOperation.PUSH);

        assertEquals(2, trail.steps().size(), "later rules are still recorded even though the first match decides");
        assertTrue(trail.steps().get(0).matched());
        assertTrue(trail.steps().get(1).matched());
        assertInstanceOf(UrlRuleEvaluator.Result.Denied.class, trail.result());
        assertEquals(denyRule.getId(), ((UrlRuleEvaluator.Result.Denied) trail.result()).ruleId());
    }

    @Test
    void evaluateTrail_noRulesMatch_notAllowedWithFullTrail() {
        var evaluator = evaluatorWith(allow(MatchTarget.OWNER, "myorg"));

        var trail = evaluator.evaluateTrail("otherorg/repo", "otherorg", "repo", HttpOperation.PUSH);

        assertEquals(1, trail.steps().size());
        assertFalse(trail.steps().get(0).matched());
        assertInstanceOf(UrlRuleEvaluator.Result.NotAllowed.class, trail.result());
    }

    @Test
    void evaluateTrail_emptyRegistry_emptyTrail() {
        var evaluator = new UrlRuleEvaluator(new InMemoryUrlRuleRegistry(), GITHUB);

        var trail = evaluator.evaluateTrail("org/repo", "org", "repo", HttpOperation.PUSH);

        assertTrue(trail.steps().isEmpty());
        assertInstanceOf(UrlRuleEvaluator.Result.NotAllowed.class, trail.result());
    }

    // ── Registry query ────────────────────────────────────────────────────────

    @Test
    void registry_fetchedOnce() {
        UrlRuleRegistry registry = mock(UrlRuleRegistry.class);
        when(registry.findEnabledForProvider(GITHUB.getProviderId())).thenReturn(List.of());

        var evaluator = new UrlRuleEvaluator(registry, GITHUB);
        evaluator.evaluate("org/repo", "org", "repo", HttpOperation.PUSH);

        verify(registry, times(1)).findEnabledForProvider(GITHUB.getProviderId());
    }

    // ── Pattern helpers ───────────────────────────────────────────────────────

    @Test
    void matchPattern_literal_exactMatch() {
        assertTrue(UrlRuleEvaluator.matchPattern("myorg", MatchType.LITERAL, "myorg"));
        assertFalse(UrlRuleEvaluator.matchPattern("myorg", MatchType.LITERAL, "otherorg"));
    }

    @Test
    void matchPattern_literal_leadingSlashNotNormalised() {
        // A slug pattern is compared against the request path verbatim: the leading '/' is part of the value on both
        // sides, and a rule that omits it does not match.
        assertTrue(UrlRuleEvaluator.matchPattern("/owner/repo", MatchType.LITERAL, "/owner/repo"));
        assertFalse(UrlRuleEvaluator.matchPattern("/owner/repo", MatchType.LITERAL, "owner/repo"));
        assertFalse(UrlRuleEvaluator.matchPattern("owner/repo", MatchType.LITERAL, "/owner/repo"));
    }

    @Test
    void matchPattern_glob_wildcard() {
        assertTrue(UrlRuleEvaluator.matchPattern("myorg-*", MatchType.GLOB, "myorg-internal"));
        assertFalse(UrlRuleEvaluator.matchPattern("myorg-*", MatchType.GLOB, "otherorg-internal"));
    }

    @Test
    void matchPattern_glob_leadingSlashNotNormalised() {
        assertTrue(UrlRuleEvaluator.matchPattern("/myorg/*", MatchType.GLOB, "/myorg/repo"));
        assertFalse(UrlRuleEvaluator.matchPattern("myorg/*", MatchType.GLOB, "/myorg/repo"));
    }

    @Test
    void matchPattern_glob_slugDoubleStarCrossesNestedNamespace() {
        assertTrue(UrlRuleEvaluator.matchPattern("/group/**", MatchType.GLOB, "/group/subgroup/project"));
        assertFalse(UrlRuleEvaluator.matchPattern("/group/*", MatchType.GLOB, "/group/subgroup/project"));
    }

    @Test
    void matchPattern_regex_matchesRawValue() {
        assertTrue(UrlRuleEvaluator.matchPattern("^(myorg|partnerorg)$", MatchType.REGEX, "myorg"));
        assertTrue(UrlRuleEvaluator.matchPattern("/myorg/.*", MatchType.REGEX, "/myorg/any-repo"));
        assertFalse(UrlRuleEvaluator.matchPattern("^(myorg|partnerorg)$", MatchType.REGEX, "otherog"));
    }

    @Test
    void matchPattern_nullInputs_returnsFalse() {
        assertFalse(UrlRuleEvaluator.matchPattern(null, MatchType.LITERAL, "value"));
        assertFalse(UrlRuleEvaluator.matchPattern("pattern", MatchType.LITERAL, null));
    }

    // ── Case folding ──────────────────────────────────────────────────────────

    @Test
    void matchPattern_literal_ignoresCase() {
        assertTrue(UrlRuleEvaluator.matchPattern("/acme/widgets", MatchType.LITERAL, "/Acme/Widgets"));
        assertTrue(UrlRuleEvaluator.matchPattern("/Acme/Widgets", MatchType.LITERAL, "/acme/widgets"));
    }

    @Test
    void matchPattern_glob_ignoresCase() {
        assertTrue(UrlRuleEvaluator.matchPattern("/acme/*", MatchType.GLOB, "/ACME/widgets"));
        assertTrue(UrlRuleEvaluator.matchPattern("/acme/service-*", MatchType.GLOB, "/Acme/Service-API"));
        assertTrue(UrlRuleEvaluator.matchPattern("/group/**", MatchType.GLOB, "/Group/SubGroup/Project"));
    }

    @Test
    void matchPattern_regex_ignoresCaseWithoutInlineFlag() {
        // (?i) remains valid but is no longer needed for a rule to cover a recased path.
        assertTrue(UrlRuleEvaluator.matchPattern("/acme/.*", MatchType.REGEX, "/ACME/repo"));
        assertTrue(UrlRuleEvaluator.matchPattern("(?i)/acme/.*", MatchType.REGEX, "/ACME/repo"));
        assertFalse(UrlRuleEvaluator.matchPattern("/acme/.*", MatchType.REGEX, "/other/repo"));
    }

    @Test
    void matchPattern_caseFoldingLeavesThePathShapeAlone() {
        // Folding case must not also start forgiving a missing leading slash or a crossed segment boundary.
        assertFalse(UrlRuleEvaluator.matchPattern("/acme/widgets", MatchType.LITERAL, "Acme/Widgets"));
        assertFalse(UrlRuleEvaluator.matchPattern("/acme/*", MatchType.GLOB, "/Acme/Sub/Widgets"));
    }

    // ── operationMatches helper ───────────────────────────────────────────────

    @Test
    void operationMatches_both_alwaysTrue() {
        var rule = AccessRule.builder()
                .target(MatchTarget.SLUG)
                .value("x")
                .operation(AccessRule.Operation.BOTH)
                .build();
        assertTrue(UrlRuleEvaluator.operationMatches(rule, HttpOperation.PUSH));
        assertTrue(UrlRuleEvaluator.operationMatches(rule, HttpOperation.FETCH));
    }

    @Test
    void operationMatches_pushOnly() {
        var rule = AccessRule.builder()
                .target(MatchTarget.SLUG)
                .value("x")
                .operation(AccessRule.Operation.PUSH)
                .build();
        assertTrue(UrlRuleEvaluator.operationMatches(rule, HttpOperation.PUSH));
        assertFalse(UrlRuleEvaluator.operationMatches(rule, HttpOperation.FETCH));
    }

    @Test
    void operationMatches_fetchOnly() {
        var rule = AccessRule.builder()
                .target(MatchTarget.SLUG)
                .value("x")
                .operation(AccessRule.Operation.FETCH)
                .build();
        assertFalse(UrlRuleEvaluator.operationMatches(rule, HttpOperation.PUSH));
        assertTrue(UrlRuleEvaluator.operationMatches(rule, HttpOperation.FETCH));
    }
}
