package org.finos.gitproxy.servlet.filter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;
import org.finos.gitproxy.db.RepoRegistry;
import org.finos.gitproxy.db.model.AccessRule;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.provider.GitHubProvider;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UrlRuleEvaluator}. Each test exercises a specific branch of the evaluation algorithm without
 * any Servlet or JGit machinery, confirming that the same logic applies to both proxy-mode and store-and-forward mode.
 */
class UrlRuleEvaluatorTest {

    private static final GitProxyProvider GITHUB = new GitHubProvider("/proxy");

    private static UrlRuleFilter allow(UrlRuleFilter.Target target, String... entries) {
        return new UrlRuleFilter(100, GITHUB, List.of(entries), target, AccessRule.Access.ALLOW);
    }

    private static UrlRuleFilter deny(UrlRuleFilter.Target target, String... entries) {
        return new UrlRuleFilter(100, GITHUB, List.of(entries), target, AccessRule.Access.DENY);
    }

    private static UrlRuleFilter allow(Set<HttpOperation> ops, UrlRuleFilter.Target target, String... entries) {
        return new UrlRuleFilter(100, ops, GITHUB, List.of(entries), target, AccessRule.Access.ALLOW);
    }

    private static UrlRuleFilter deny(Set<HttpOperation> ops, UrlRuleFilter.Target target, String... entries) {
        return new UrlRuleFilter(100, ops, GITHUB, List.of(entries), target, AccessRule.Access.DENY);
    }

    // ── Open mode ─────────────────────────────────────────────────────────────

    @Test
    void noRules_openMode() {
        var evaluator = new UrlRuleEvaluator(List.of(), null, null);
        assertInstanceOf(
                UrlRuleEvaluator.Result.OpenMode.class,
                evaluator.evaluate("org/repo", "org", "repo", HttpOperation.PUSH));
    }

    @Test
    void emptyConfigRules_noRegistry_openMode() {
        var evaluator = new UrlRuleEvaluator(List.of(), null, GITHUB);
        assertInstanceOf(
                UrlRuleEvaluator.Result.OpenMode.class,
                evaluator.evaluate("org/repo", "org", "repo", HttpOperation.FETCH));
    }

    // ── Allow rules ───────────────────────────────────────────────────────────

    @Test
    void configAllowRule_ownerMatch_allowed() {
        var evaluator = new UrlRuleEvaluator(List.of(allow(UrlRuleFilter.Target.OWNER, "myorg")), null, GITHUB);
        assertInstanceOf(
                UrlRuleEvaluator.Result.Allowed.class,
                evaluator.evaluate("myorg/repo", "myorg", "repo", HttpOperation.PUSH));
    }

    @Test
    void configAllowRule_slugMatch_allowed() {
        var evaluator = new UrlRuleEvaluator(List.of(allow(UrlRuleFilter.Target.SLUG, "/myorg/repo")), null, GITHUB);
        assertInstanceOf(
                UrlRuleEvaluator.Result.Allowed.class,
                evaluator.evaluate("/myorg/repo", "myorg", "repo", HttpOperation.PUSH));
    }

    @Test
    void configAllowRule_nameGlob_allowed() {
        var evaluator = new UrlRuleEvaluator(List.of(allow(UrlRuleFilter.Target.NAME, "feature-*")), null, GITHUB);
        assertInstanceOf(
                UrlRuleEvaluator.Result.Allowed.class,
                evaluator.evaluate("org/feature-abc", "org", "feature-abc", HttpOperation.PUSH));
        assertInstanceOf(
                UrlRuleEvaluator.Result.NotAllowed.class,
                evaluator.evaluate("org/main-branch", "org", "main-branch", HttpOperation.PUSH));
    }

    @Test
    void configAllowRule_noMatch_notAllowed() {
        var evaluator = new UrlRuleEvaluator(List.of(allow(UrlRuleFilter.Target.OWNER, "myorg")), null, GITHUB);
        assertInstanceOf(
                UrlRuleEvaluator.Result.NotAllowed.class,
                evaluator.evaluate("otherorg/repo", "otherorg", "repo", HttpOperation.PUSH));
    }

    // ── Deny rules ────────────────────────────────────────────────────────────

    @Test
    void configDenyRule_match_denied() {
        var evaluator = new UrlRuleEvaluator(
                List.of(deny(UrlRuleFilter.Target.OWNER, "blocked"), allow(UrlRuleFilter.Target.OWNER, "blocked")),
                null,
                GITHUB);
        assertInstanceOf(
                UrlRuleEvaluator.Result.Denied.class,
                evaluator.evaluate("blocked/repo", "blocked", "repo", HttpOperation.PUSH));
    }

    @Test
    void configDenyRule_noMatch_allowRuleChecked() {
        var evaluator = new UrlRuleEvaluator(
                List.of(deny(UrlRuleFilter.Target.OWNER, "blocked"), allow(UrlRuleFilter.Target.OWNER, "allowed")),
                null,
                GITHUB);
        assertInstanceOf(
                UrlRuleEvaluator.Result.Allowed.class,
                evaluator.evaluate("allowed/repo", "allowed", "repo", HttpOperation.PUSH));
    }

    // ── Operations filtering — the core consistency guarantee ─────────────────

    @Test
    void fetchOnlyAllowRule_doesNotCountForPushOpenModeDetection() {
        // A FETCH-only allow rule must not prevent open-mode for pushes.
        var fetchAllow = allow(Set.of(HttpOperation.FETCH), UrlRuleFilter.Target.OWNER, "myorg");
        var evaluator = new UrlRuleEvaluator(List.of(fetchAllow), null, GITHUB);
        assertInstanceOf(
                UrlRuleEvaluator.Result.OpenMode.class,
                evaluator.evaluate("myorg/repo", "myorg", "repo", HttpOperation.PUSH),
                "FETCH-only allow rule should not engage for PUSH — push should stay in open mode");
    }

    @Test
    void pushOnlyAllowRule_doesNotMatchFetch() {
        var pushAllow = allow(Set.of(HttpOperation.PUSH), UrlRuleFilter.Target.OWNER, "myorg");
        var evaluator = new UrlRuleEvaluator(List.of(pushAllow), null, GITHUB);
        assertInstanceOf(
                UrlRuleEvaluator.Result.OpenMode.class,
                evaluator.evaluate("myorg/repo", "myorg", "repo", HttpOperation.FETCH),
                "PUSH-only allow rule should not engage for FETCH — fetch should stay in open mode");
    }

    @Test
    void fetchOnlyDenyRule_doesNotBlockPush() {
        // A FETCH-only deny rule must not block a push.
        var fetchDeny = deny(Set.of(HttpOperation.FETCH), UrlRuleFilter.Target.OWNER, "myorg");
        var pushAllow = allow(UrlRuleFilter.Target.OWNER, "myorg");
        var evaluator = new UrlRuleEvaluator(List.of(fetchDeny, pushAllow), null, GITHUB);
        assertInstanceOf(
                UrlRuleEvaluator.Result.Allowed.class,
                evaluator.evaluate("myorg/repo", "myorg", "repo", HttpOperation.PUSH),
                "FETCH-only deny rule must not block a push");
    }

    @Test
    void pushOnlyDenyRule_doesNotBlockFetch() {
        var pushDeny = deny(Set.of(HttpOperation.PUSH), UrlRuleFilter.Target.OWNER, "myorg");
        var fetchAllow = allow(UrlRuleFilter.Target.OWNER, "myorg");
        var evaluator = new UrlRuleEvaluator(List.of(pushDeny, fetchAllow), null, GITHUB);
        assertInstanceOf(
                UrlRuleEvaluator.Result.Allowed.class,
                evaluator.evaluate("myorg/repo", "myorg", "repo", HttpOperation.FETCH),
                "PUSH-only deny rule must not block a fetch");
    }

    // ── DB rules ──────────────────────────────────────────────────────────────

    @Test
    void dbAllowRule_slugMatch_allowed() {
        RepoRegistry registry = mock(RepoRegistry.class);
        var rule = AccessRule.builder()
                .slug("/myorg/repo")
                .access(AccessRule.Access.ALLOW)
                .build();
        when(registry.findEnabledForProvider(GITHUB.getProviderId())).thenReturn(List.of(rule));

        var evaluator = new UrlRuleEvaluator(List.of(), registry, GITHUB);
        assertInstanceOf(
                UrlRuleEvaluator.Result.Allowed.class,
                evaluator.evaluate("/myorg/repo", "myorg", "repo", HttpOperation.PUSH));
    }

    @Test
    void dbDenyRule_blocks() {
        RepoRegistry registry = mock(RepoRegistry.class);
        var deny = AccessRule.builder()
                .slug("/myorg/repo")
                .access(AccessRule.Access.DENY)
                .build();
        var allow = AccessRule.builder()
                .slug("/myorg/repo")
                .access(AccessRule.Access.ALLOW)
                .build();
        when(registry.findEnabledForProvider(GITHUB.getProviderId())).thenReturn(List.of(deny, allow));

        var evaluator = new UrlRuleEvaluator(List.of(), registry, GITHUB);
        assertInstanceOf(
                UrlRuleEvaluator.Result.Denied.class,
                evaluator.evaluate("/myorg/repo", "myorg", "repo", HttpOperation.PUSH));
    }

    @Test
    void dbFetchOnlyAllowRule_doesNotMatchPush() {
        RepoRegistry registry = mock(RepoRegistry.class);
        var fetchRule = AccessRule.builder()
                .slug("/myorg/repo")
                .access(AccessRule.Access.ALLOW)
                .operations(AccessRule.Operations.FETCH)
                .build();
        when(registry.findEnabledForProvider(GITHUB.getProviderId())).thenReturn(List.of(fetchRule));

        var evaluator = new UrlRuleEvaluator(List.of(), registry, GITHUB);
        assertInstanceOf(
                UrlRuleEvaluator.Result.OpenMode.class,
                evaluator.evaluate("/myorg/repo", "myorg", "repo", HttpOperation.PUSH),
                "DB FETCH-only allow rule must not engage for push — should be open mode");
    }

    @Test
    void dbFetchOnlyDenyRule_doesNotBlockPush() {
        RepoRegistry registry = mock(RepoRegistry.class);
        var fetchDeny = AccessRule.builder()
                .slug("/myorg/repo")
                .access(AccessRule.Access.DENY)
                .operations(AccessRule.Operations.FETCH)
                .build();
        var pushAllow = AccessRule.builder()
                .slug("/myorg/repo")
                .access(AccessRule.Access.ALLOW)
                .operations(AccessRule.Operations.BOTH)
                .build();
        when(registry.findEnabledForProvider(GITHUB.getProviderId())).thenReturn(List.of(fetchDeny, pushAllow));

        var evaluator = new UrlRuleEvaluator(List.of(), registry, GITHUB);
        assertInstanceOf(
                UrlRuleEvaluator.Result.Allowed.class,
                evaluator.evaluate("/myorg/repo", "myorg", "repo", HttpOperation.PUSH),
                "DB FETCH-only deny rule must not block a push");
    }

    @Test
    void dbRules_fetchedOnce() {
        // Registry must be queried only once per evaluate() call, not once for deny + once for allow.
        RepoRegistry registry = mock(RepoRegistry.class);
        when(registry.findEnabledForProvider(GITHUB.getProviderId())).thenReturn(List.of());

        var evaluator = new UrlRuleEvaluator(List.of(), registry, GITHUB);
        evaluator.evaluate("org/repo", "org", "repo", HttpOperation.PUSH);

        verify(registry, times(1)).findEnabledForProvider(GITHUB.getProviderId());
    }

    // ── Pattern helpers ───────────────────────────────────────────────────────

    @Test
    void matchPattern_literal_exactMatch() {
        assertTrue(UrlRuleEvaluator.matchPattern("myorg", "myorg"));
        assertFalse(UrlRuleEvaluator.matchPattern("myorg", "otherorg"));
    }

    @Test
    void matchPattern_literal_leadingSlashNormalised() {
        // /owner/repo and owner/repo should match each other regardless of leading slash
        assertTrue(UrlRuleEvaluator.matchPattern("/owner/repo", "owner/repo"));
        assertTrue(UrlRuleEvaluator.matchPattern("owner/repo", "/owner/repo"));
    }

    @Test
    void matchPattern_glob_wildcard() {
        assertTrue(UrlRuleEvaluator.matchPattern("myorg-*", "myorg-internal"));
        assertFalse(UrlRuleEvaluator.matchPattern("myorg-*", "otherorg-internal"));
    }

    @Test
    void matchPattern_regex_matchesRawValue() {
        // Regex patterns receive the raw value (with leading slash if present)
        assertTrue(UrlRuleEvaluator.matchPattern("regex:^(myorg|partnerorg)$", "myorg"));
        assertTrue(UrlRuleEvaluator.matchPattern("regex:/myorg/.*", "/myorg/any-repo"));
        assertFalse(UrlRuleEvaluator.matchPattern("regex:^(myorg|partnerorg)$", "otherog"));
    }

    @Test
    void matchPattern_nullInputs_returnsFalse() {
        assertFalse(UrlRuleEvaluator.matchPattern(null, "value"));
        assertFalse(UrlRuleEvaluator.matchPattern("pattern", null));
    }

    // ── operationMatches helper ───────────────────────────────────────────────

    @Test
    void operationMatches_both_alwaysTrue() {
        var rule = AccessRule.builder()
                .slug("x")
                .operations(AccessRule.Operations.BOTH)
                .build();
        assertTrue(UrlRuleEvaluator.operationMatches(rule, HttpOperation.PUSH));
        assertTrue(UrlRuleEvaluator.operationMatches(rule, HttpOperation.FETCH));
    }

    @Test
    void operationMatches_pushOnly() {
        var rule = AccessRule.builder()
                .slug("x")
                .operations(AccessRule.Operations.PUSH)
                .build();
        assertTrue(UrlRuleEvaluator.operationMatches(rule, HttpOperation.PUSH));
        assertFalse(UrlRuleEvaluator.operationMatches(rule, HttpOperation.FETCH));
    }

    @Test
    void operationMatches_fetchOnly() {
        var rule = AccessRule.builder()
                .slug("x")
                .operations(AccessRule.Operations.FETCH)
                .build();
        assertFalse(UrlRuleEvaluator.operationMatches(rule, HttpOperation.PUSH));
        assertTrue(UrlRuleEvaluator.operationMatches(rule, HttpOperation.FETCH));
    }
}
