package com.rbc.fogwall.servlet.filter;

import com.rbc.fogwall.db.UrlRuleRegistry;
import com.rbc.fogwall.db.model.AccessRule;
import com.rbc.fogwall.db.model.MatchType;
import com.rbc.fogwall.git.HttpOperation;
import com.rbc.fogwall.git.RepoPathMatching;
import com.rbc.fogwall.git.RepositoryUrlRuleHook;
import com.rbc.fogwall.provider.FogwallProvider;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Pure-logic rule evaluator shared by both proxy-mode ({@link UrlRuleAggregateFilter}) and server mode
 * ({@link RepositoryUrlRuleHook}). Contains no Servlet or JGit dependencies.
 *
 * <p>Evaluation uses firewall / iptables semantics: all matching rules (config and DB) are collected, sorted by
 * {@code order} ascending, and the first match wins regardless of whether it is an allow or deny rule. Rules from both
 * sources participate in the same ordered list — the origin of a rule (config file vs. database) has no effect on
 * priority. If two rules have the same order value and both match, a warning is logged and the result is unspecified.
 *
 * <p>If no rule matches the request, the proxy is fail-closed and returns {@link Result.NotAllowed}.
 */
@Slf4j
public class UrlRuleEvaluator {

    /** Outcome of a single rule evaluation pass. */
    public sealed interface Result permits Result.Denied, Result.Allowed, Result.NotAllowed {

        /** A deny rule matched — request must be rejected. */
        record Denied(String ruleId) implements Result {}

        /** An allow rule matched — request may proceed. */
        record Allowed(String ruleId) implements Result {}

        /** No rule matched — request must be rejected (fail-closed). */
        record NotAllowed() implements Result {}
    }

    /** One rule considered during evaluation, in order, and whether it matched the repository reference. */
    public record RuleEvaluation(AccessRule rule, boolean matched) {}

    /** The full ordered evaluation trail plus the resulting decision. */
    public record Trail(List<RuleEvaluation> steps, Result result) {}

    private static final ConcurrentHashMap<String, Pattern> REGEX_CACHE = new ConcurrentHashMap<>();

    private final UrlRuleRegistry urlRuleRegistry;
    private final FogwallProvider provider;

    public UrlRuleEvaluator(UrlRuleRegistry urlRuleRegistry, FogwallProvider provider) {
        this.urlRuleRegistry = urlRuleRegistry;
        this.provider = provider;
    }

    /**
     * Evaluates all configured rules for the given repository reference and operation.
     *
     * @param slug full path slug (e.g. {@code "owner/repo"} or {@code "/owner/repo"})
     * @param owner repository owner / organisation
     * @param name repository name
     * @param operation the HTTP operation being evaluated
     * @return the evaluation result
     */
    public Result evaluate(String slug, String owner, String name, HttpOperation operation) {
        List<AccessRule> rules = (urlRuleRegistry != null && provider != null)
                ? urlRuleRegistry.findEnabledForProvider(provider.getProviderId())
                : List.of();

        List<AccessRule> sortedAll = rules.stream()
                .filter(r -> operationMatches(r, operation))
                .sorted(Comparator.comparingInt(AccessRule::getRuleOrder))
                .toList();

        for (AccessRule r : sortedAll) {
            if (matchesRepo(r, slug, owner, name)) {
                if (r.getAccess() == AccessRule.Access.DENY) {
                    log.debug("Denied by rule (order {}, source {}): {}", r.getRuleOrder(), r.getSource(), r.getId());
                    return new Result.Denied(r.getId());
                } else {
                    log.debug("Allowed by rule (order {}, source {}): {}", r.getRuleOrder(), r.getSource(), r.getId());
                    return new Result.Allowed(r.getId());
                }
            }
        }

        return new Result.NotAllowed();
    }

    /**
     * Evaluates all configured rules like {@link #evaluate}, but instead of stopping at the first match, continues
     * through every enabled rule (in order) and records whether each one matched. Used by the admin-facing rule test
     * endpoint to show a firewall-log style trail; the live push/proxy path uses {@link #evaluate} since it only needs
     * the final decision.
     */
    public Trail evaluateTrail(String slug, String owner, String name, HttpOperation operation) {
        List<AccessRule> rules = (urlRuleRegistry != null && provider != null)
                ? urlRuleRegistry.findEnabledForProvider(provider.getProviderId())
                : List.of();

        List<AccessRule> sortedAll = rules.stream()
                .filter(r -> operationMatches(r, operation))
                .sorted(Comparator.comparingInt(AccessRule::getRuleOrder))
                .toList();

        List<RuleEvaluation> steps = new ArrayList<>();
        Result result = null;
        for (AccessRule r : sortedAll) {
            boolean matched = matchesRepo(r, slug, owner, name);
            steps.add(new RuleEvaluation(r, matched));
            if (matched && result == null) {
                result = r.getAccess() == AccessRule.Access.DENY
                        ? new Result.Denied(r.getId())
                        : new Result.Allowed(r.getId());
            }
        }
        if (result == null) {
            result = new Result.NotAllowed();
        }
        return new Trail(List.copyOf(steps), result);
    }

    /**
     * Returns {@code true} if the rule's {@code operations} field is compatible with the requested {@code operation}.
     * {@code BOTH} matches everything; {@code PUSH} matches only push; {@code FETCH} matches only fetch.
     */
    static boolean operationMatches(AccessRule rule, HttpOperation operation) {
        return switch (rule.getOperation()) {
            case BOTH -> true;
            case PUSH -> operation == HttpOperation.PUSH;
            case FETCH -> operation == HttpOperation.FETCH;
        };
    }

    /** Returns {@code true} if the given {@link AccessRule} matches the repository reference. */
    static boolean matchesRepo(AccessRule rule, String slug, String owner, String name) {
        String candidate =
                switch (rule.getTarget()) {
                    case SLUG -> slug;
                    case OWNER -> owner;
                    case NAME -> name;
                };
        return matchPattern(rule.getValue(), rule.getMatchType(), candidate);
    }

    /**
     * Matches a pattern string against a value using the specified {@link MatchType}. No path <em>shape</em> is
     * normalised for any match type — the pattern is compared against the candidate exactly as both are written. These
     * are URL rules, so a {@code SLUG} pattern carries the leading {@code /} the request path has (`/acme/repo`), while
     * {@code OWNER} and {@code NAME} patterns are bare path segments. Normalising a leading {@code /} for some match
     * types and not others is how the same rule came to match in one proxy mode and not the other.
     *
     * <p>Case is the exception, and is folded for every match type via {@link RepoPathMatching} — a differently-cased
     * path names the same repository upstream, so it must not be able to slip past a deny rule.
     */
    static boolean matchPattern(String pattern, MatchType matchType, String value) {
        if (pattern == null || value == null) return false;
        return switch (matchType) {
            case REGEX ->
                REGEX_CACHE
                        .computeIfAbsent(pattern, p -> Pattern.compile(p, RepoPathMatching.REGEX_FLAGS))
                        .matcher(value)
                        .matches();
            case GLOB -> RepoPathMatching.globMatches(pattern, value);
            case LITERAL -> RepoPathMatching.literalMatches(pattern, value);
        };
    }
}
