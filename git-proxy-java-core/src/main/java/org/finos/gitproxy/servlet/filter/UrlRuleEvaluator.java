package org.finos.gitproxy.servlet.filter;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.db.RepoRegistry;
import org.finos.gitproxy.db.model.AccessRule;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.provider.GitProxyProvider;

/**
 * Pure-logic rule evaluator shared by both proxy-mode ({@link UrlRuleAggregateFilter}) and store-and-forward mode
 * ({@link org.finos.gitproxy.git.RepositoryUrlRuleHook}). Contains no Servlet or JGit dependencies.
 *
 * <p>Evaluation order:
 *
 * <ol>
 *   <li>Config deny rules — first match returns {@link Result.Denied}.
 *   <li>DB deny rules — first match returns {@link Result.Denied}.
 *   <li>If no allow rules are configured anywhere — returns {@link Result.OpenMode} (implicitly allowed).
 *   <li>Config allow rules — first match returns {@link Result.Allowed}.
 *   <li>DB allow rules — first match returns {@link Result.Allowed}.
 *   <li>Allow rules exist but none matched — returns {@link Result.NotAllowed}.
 * </ol>
 *
 * <p>DB rules are fetched once per evaluation (not twice), then split into deny/allow lists in memory.
 */
@Slf4j
public class UrlRuleEvaluator {

    /** Outcome of a single rule evaluation pass. */
    public sealed interface Result permits Result.Denied, Result.Allowed, Result.OpenMode, Result.NotAllowed {

        /** A deny rule matched — request must be rejected. */
        record Denied(String ruleId) implements Result {}

        /** An allow rule matched — request may proceed. */
        record Allowed(String ruleId) implements Result {}

        /**
         * No allow rules are configured anywhere (neither config nor DB). The proxy is in open/permissive mode and the
         * request may proceed.
         */
        record OpenMode() implements Result {}

        /** Allow rules are configured but none matched — request must be rejected. */
        record NotAllowed() implements Result {}
    }

    private static final ConcurrentHashMap<String, Pattern> REGEX_CACHE = new ConcurrentHashMap<>();

    private final List<UrlRuleFilter> configRules;
    private final RepoRegistry repoRegistry;
    private final GitProxyProvider provider;

    public UrlRuleEvaluator(List<UrlRuleFilter> configRules, RepoRegistry repoRegistry, GitProxyProvider provider) {
        this.configRules = configRules != null ? configRules : List.of();
        this.repoRegistry = repoRegistry;
        this.provider = provider;
    }

    /**
     * Evaluates all configured rules for the given repository reference and operation. DB rules are fetched once from
     * the registry and split into deny/allow lists in memory.
     *
     * @param slug full path slug (e.g. {@code "owner/repo"} or {@code "/owner/repo"})
     * @param owner repository owner / organisation
     * @param name repository name
     * @param operation the HTTP operation being evaluated
     * @return the evaluation result
     */
    public Result evaluate(String slug, String owner, String name, HttpOperation operation) {
        // Fetch DB rules once — split into deny/allow in memory to avoid two registry queries
        List<AccessRule> dbRules = (repoRegistry != null && provider != null)
                ? repoRegistry.findEnabledForProvider(provider.getProviderId())
                : List.of();

        // ── Step 1: deny rules ─────────────────────────────────────────────────
        for (UrlRuleFilter f : configRules) {
            if (f.getAccess() == AccessRule.Access.DENY && f.appliesTo(operation) && f.matchesRepo(slug, owner, name)) {
                log.debug("Denied by config rule: {}", f);
                return new Result.Denied(f.toString());
            }
        }
        for (AccessRule rule : dbRules) {
            if (rule.getAccess() == AccessRule.Access.DENY
                    && operationMatches(rule, operation)
                    && matchesRepo(rule, slug, owner, name)) {
                log.debug("Denied by DB rule: id={}", rule.getId());
                return new Result.Denied(rule.getId());
            }
        }

        // ── Step 2: allow rules ────────────────────────────────────────────────
        List<UrlRuleFilter> configAllow = configRules.stream()
                .filter(f -> f.getAccess() == AccessRule.Access.ALLOW && f.appliesTo(operation))
                .toList();
        List<AccessRule> dbAllow = dbRules.stream()
                .filter(r -> r.getAccess() == AccessRule.Access.ALLOW && operationMatches(r, operation))
                .toList();

        if (configAllow.isEmpty() && dbAllow.isEmpty()) {
            log.debug("No allow rules configured for operation {} — open mode", operation);
            return new Result.OpenMode();
        }

        for (UrlRuleFilter f : configAllow) {
            if (f.matchesRepo(slug, owner, name)) {
                log.debug("Allowed by config rule: {}", f);
                return new Result.Allowed(f.toString());
            }
        }
        for (AccessRule rule : dbAllow) {
            if (matchesRepo(rule, slug, owner, name)) {
                log.debug("Allowed by DB rule: id={}", rule.getId());
                return new Result.Allowed(rule.getId());
            }
        }

        return new Result.NotAllowed();
    }

    /**
     * Returns {@code true} if the rule's {@code operations} field is compatible with the requested {@code operation}.
     * {@code BOTH} matches everything; {@code PUSH} matches only push; {@code FETCH} matches only fetch.
     */
    static boolean operationMatches(AccessRule rule, HttpOperation operation) {
        return switch (rule.getOperations()) {
            case BOTH -> true;
            case PUSH -> operation == HttpOperation.PUSH;
            case FETCH -> operation == HttpOperation.FETCH;
        };
    }

    /**
     * Returns {@code true} if the given {@link AccessRule} matches the repository reference. Slug/owner/name
     * comparisons strip any leading {@code /} so that stored values like {@code coopernetes/repo} and
     * {@code /coopernetes/repo} both match regardless of how the rule was saved.
     */
    /**
     * Returns {@code true} if the given {@link AccessRule} matches the repository reference.
     *
     * <p>For slug rules: literal and glob comparisons strip leading {@code /} from both pattern and value so that
     * {@code /owner/repo} and {@code owner/repo} are treated identically. Regex patterns receive the raw slug (with
     * leading {@code /} if present) so users can write them relative to the full path form (e.g.
     * {@code regex:/myorg/.*}).
     */
    static boolean matchesRepo(AccessRule rule, String slug, String owner, String name) {
        if (rule.getSlug() != null) return matchPattern(rule.getSlug(), slug);
        if (rule.getOwner() != null) return matchPattern(rule.getOwner(), owner);
        if (rule.getName() != null) return matchPattern(rule.getName(), name);
        return false;
    }

    /**
     * Matches a pattern string against a value. Regex patterns (prefixed with {@code regex:}) are matched against the
     * raw value as-is. Literal and glob patterns normalise leading {@code /} from both sides before comparison.
     */
    static boolean matchPattern(String pattern, String value) {
        if (pattern == null || value == null) return false;
        if (pattern.startsWith("regex:")) {
            // Regex: match against the raw value — users write patterns relative to the full slug form
            return REGEX_CACHE
                    .computeIfAbsent(pattern.substring(6), Pattern::compile)
                    .matcher(value)
                    .matches();
        }
        // Literal / glob: normalise leading slashes so /owner/repo and owner/repo are equivalent
        String p = strip(pattern);
        String v = strip(value);
        if (p.equals(v)) return true;
        if (p.contains("*") || p.contains("?") || p.contains("[")) {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + p);
            return matcher.matches(Paths.get(v));
        }
        return false;
    }

    private static String strip(String s) {
        return (s != null && s.startsWith("/")) ? s.substring(1) : s;
    }
}
