package com.rbc.fogwall.permission;

import com.rbc.fogwall.db.model.MatchTarget;
import com.rbc.fogwall.db.model.MatchType;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.extern.slf4j.Slf4j;

/**
 * Service that evaluates whether a proxy user is authorised to push to or approve a push for a given repository.
 *
 * <h2>Fail-closed semantics</h2>
 *
 * <p>If <em>any</em> permission rows exist for the {@code (provider, path)} combination the request is denied unless
 * the user appears in the matching set for the requested operation. If <em>no</em> rows match the path the request is
 * also denied — the permission store must explicitly enumerate every permitted user.
 *
 * <h3>Path matching</h3>
 *
 * <p>Paths use the {@code /owner/repo} convention (leading slash, no {@code .git} suffix). Matching is controlled by
 * {@link MatchType}:
 *
 * <ul>
 *   <li>{@code LITERAL} — exact string equality
 *   <li>{@code GLOB} — {@link java.nio.file.FileSystem#getPathMatcher} with {@code glob:} prefix; {@code *} matches one
 *       path segment, {@code **} matches any depth
 *   <li>{@code REGEX} — full Java regex matched against the full path string
 * </ul>
 */
@Slf4j
public class RepoPermissionService {

    /** Outcome of evaluating whether a user has a given grant for a path, and what granted it. */
    public sealed interface GrantResult
            permits GrantResult.GrantedDirect, GrantResult.GrantedByGroup, GrantResult.NotGranted {

        /** A direct, per-user permission entry granted access. */
        record GrantedDirect(RepoPermission permission) implements GrantResult {}

        /** A group-inherited permission rule granted access. */
        record GrantedByGroup(GroupPermissionRule rule) implements GrantResult {}

        /** No direct or group permission granted access. */
        record NotGranted() implements GrantResult {}
    }

    private final PermissionStore<RepoPermission> store;
    private final GroupPermissionStore groupStore;
    private final ConcurrentHashMap<String, Pattern> patternCache = new ConcurrentHashMap<>();

    public RepoPermissionService(PermissionStore<RepoPermission> store) {
        this(store, null);
    }

    public RepoPermissionService(PermissionStore<RepoPermission> store, GroupPermissionStore groupStore) {
        this.store = store;
        this.groupStore = groupStore;
    }

    /**
     * Returns {@code true} when {@code username} is authorised to push to {@code path} at {@code provider}.
     * Fail-closed: returns {@code false} if no grants exist for the path.
     */
    public boolean isAllowedToPush(String username, String provider, String path) {
        return isAllowed(username, provider, path, RepoPermission.Grant.PUSH);
    }

    /**
     * Returns {@code true} when {@code username} is authorised to review (approve or reject) a push for {@code path} at
     * {@code provider}. Fail-closed: returns {@code false} if no grants exist for the path.
     */
    public boolean isAllowedToReview(String username, String provider, String path) {
        return isAllowed(username, provider, path, RepoPermission.Grant.REVIEW);
    }

    /**
     * Returns {@code true} when {@code username} is authorised to propose a change against {@code path} at
     * {@code provider} — opening a pull/merge request and iterating on it through the SCM API proxy. Fail-closed:
     * returns {@code false} if no grants exist for the path. Reads do not call this method at all — they are governed
     * by the existing URL-rule mechanism instead, the same as git fetches.
     */
    public boolean isAllowedToPropose(String username, String provider, String path) {
        return isAllowed(username, provider, path, RepoPermission.Grant.PROPOSE);
    }

    /**
     * Returns {@code true} when {@code username} has an explicit {@link RepoPermission.Grant#SELF_CERTIFY} grant for
     * {@code path} at {@code provider}. Unlike push/approve checks, {@link RepoPermission.Grant#PUSH_AND_REVIEW} does
     * <em>not</em> imply self-certify — the grant must be explicit.
     */
    public boolean isBypassReviewAllowed(String username, String provider, String path) {
        List<RepoPermission> forPath = store.findByProvider(provider).stream()
                .filter(p -> matchesPath(p, path))
                .toList();

        if (forPath.isEmpty()) {
            return false;
        }

        boolean allowed = forPath.stream()
                .filter(p -> p.getGrant() == RepoPermission.Grant.SELF_CERTIFY)
                .anyMatch(p -> username.equals(p.getUsername()));

        log.debug(
                "Bypass review check: user={} provider={} path={} → {}",
                username,
                provider,
                path,
                allowed ? "ALLOW" : "DENY");
        return allowed;
    }

    /**
     * Returns the first existing permission that would conflict with {@code incoming}, or empty if none.
     *
     * <p>Two permissions conflict when they share the same username and provider, their paths overlap (exact string
     * equality, or one pattern matches the other path string), AND their operations affect the same permission check.
     * {@link RepoPermission.Grant#SELF_CERTIFY} is evaluated by a separate code path from push/review operations, so a
     * {@code SELF_CERTIFY} entry does not conflict with a {@code PUSH_AND_REVIEW} entry on the same path — both are
     * needed for a trusted committer configuration.
     */
    public Optional<RepoPermission> findConflict(RepoPermission incoming) {
        return store.findAll().stream()
                .filter(e -> !e.getId().equals(incoming.getId()))
                .filter(e -> e.getUsername().equals(incoming.getUsername()))
                .filter(e -> e.getProvider().equals(incoming.getProvider()))
                .filter(e -> pathsOverlap(e, incoming))
                .filter(e -> grantsOverlap(e.getGrant(), incoming.getGrant()))
                .findFirst();
    }

    // ---- store delegation ----

    public void save(RepoPermission permission) {
        store.save(permission);
    }

    public void delete(String id) {
        store.delete(id);
    }

    public Optional<RepoPermission> findById(String id) {
        return store.findById(id);
    }

    public List<RepoPermission> findAll() {
        return store.findAll();
    }

    public List<RepoPermission> findByUsername(String username) {
        return store.findByUsername(username);
    }

    public List<RepoPermission> findByProvider(String provider) {
        return store.findByProvider(provider);
    }

    public GroupPermissionStore getGroupStore() {
        return groupStore;
    }

    /**
     * Seeds permissions from config on startup. Clears all CONFIG-sourced rows and re-inserts to keep YAML
     * authoritative; DB-sourced rows are left untouched.
     */
    public void seedFromConfig(List<RepoPermission> permissions) {
        // Remove existing CONFIG rows so YAML changes take effect on restart.
        store.findAll().stream()
                .filter(p -> p.getSource() == RepoPermission.Source.CONFIG)
                .forEach(p -> store.delete(p.getId()));
        for (RepoPermission p : permissions) {
            Optional<RepoPermission> conflict = findConflict(p);
            if (conflict.isPresent()) {
                RepoPermission c = conflict.get();
                throw new IllegalStateException(String.format(
                        "Conflicting permission for user '%s' provider '%s': value '%s' (%s/%s) overlaps with '%s' (%s/%s) [%s] — fix config and restart",
                        p.getUsername(),
                        p.getProvider(),
                        p.getValue(),
                        p.getTarget(),
                        p.getMatchType(),
                        c.getValue(),
                        c.getTarget(),
                        c.getMatchType(),
                        c.getSource()));
            }
            store.save(p);
        }
        log.info("Seeded {} permission grant(s) from config", permissions.size());
    }

    // ---- internals ----

    /**
     * Whether a {@code configured} grant satisfies a {@code requested} check. {@code PUSH_AND_REVIEW} is shorthand for
     * {@code PUSH} + {@code REVIEW} only — it must not also satisfy unrelated grants like {@code SELF_CERTIFY} or
     * {@code PROPOSE}, which are independent axes an operator can permission separately.
     */
    private static boolean impliesGrant(RepoPermission.Grant configured, RepoPermission.Grant requested) {
        if (configured == requested) {
            return true;
        }
        return configured == RepoPermission.Grant.PUSH_AND_REVIEW
                && (requested == RepoPermission.Grant.PUSH || requested == RepoPermission.Grant.REVIEW);
    }

    private boolean grantsOverlap(RepoPermission.Grant a, RepoPermission.Grant b) {
        // SELF_CERTIFY is evaluated by isBypassReviewAllowed(), independent of push/review checks.
        // A SELF_CERTIFY entry only conflicts with another SELF_CERTIFY entry.
        if (a == RepoPermission.Grant.SELF_CERTIFY || b == RepoPermission.Grant.SELF_CERTIFY) {
            return a == b;
        }
        // PROPOSE is a separate axis from push/review too — a repo can permission git-push and change
        // proposals independently, so a PROPOSE entry only conflicts with another PROPOSE entry.
        if (a == RepoPermission.Grant.PROPOSE || b == RepoPermission.Grant.PROPOSE) {
            return a == b;
        }
        // Among PUSH / REVIEW / PUSH_AND_REVIEW: conflict if both entries would affect the same check.
        // PUSH_AND_REVIEW overlaps with both PUSH and REVIEW; PUSH and REVIEW don't overlap each other.
        boolean aPush = a == RepoPermission.Grant.PUSH || a == RepoPermission.Grant.PUSH_AND_REVIEW;
        boolean bPush = b == RepoPermission.Grant.PUSH || b == RepoPermission.Grant.PUSH_AND_REVIEW;
        boolean aReview = a == RepoPermission.Grant.REVIEW || a == RepoPermission.Grant.PUSH_AND_REVIEW;
        boolean bReview = b == RepoPermission.Grant.REVIEW || b == RepoPermission.Grant.PUSH_AND_REVIEW;
        return (aPush && bPush) || (aReview && bReview);
    }

    private boolean pathsOverlap(RepoPermission a, RepoPermission b) {
        if (a.getValue().equals(b.getValue())) return true;
        if (matchesPath(a, b.getValue())) return true;
        if (matchesPath(b, a.getValue())) return true;
        return false;
    }

    private boolean isAllowed(String username, String provider, String path, RepoPermission.Grant op) {
        boolean allowed = !(evaluateGrant(username, provider, path, op) instanceof GrantResult.NotGranted);
        log.debug(
                "Permission check: user={} provider={} path={} op={} → {}",
                username,
                provider,
                path,
                op,
                allowed ? "ALLOW" : "DENY");
        return allowed;
    }

    /**
     * Evaluates whether {@code username} has {@code op} (or the combined {@code PUSH_AND_REVIEW} grant) for
     * {@code path} at {@code provider}, and returns which permission entry granted it — a direct per-user permission, a
     * group-inherited rule, or neither. Direct permissions are preferred over group rules when both exist.
     */
    public GrantResult evaluateGrant(String username, String provider, String path, RepoPermission.Grant op) {
        Optional<RepoPermission> direct = store.findByProvider(provider).stream()
                .filter(p -> matchesPath(p, path))
                .filter(p -> impliesGrant(p.getGrant(), op))
                .filter(p -> username.equals(p.getUsername()))
                .findFirst();
        if (direct.isPresent()) {
            return new GrantResult.GrantedDirect(direct.get());
        }

        if (groupStore != null) {
            Set<String> userGroupIds = Set.copyOf(groupStore.findGroupIdsForUser(username));
            Optional<GroupPermissionRule> viaGroup = groupStore.findRulesByProvider(provider).stream()
                    .filter(r -> userGroupIds.contains(r.getGroupId()))
                    .filter(r -> matchesPathRule(r, path))
                    .filter(r -> impliesGrant(r.getGrant(), op))
                    .findFirst();
            if (viaGroup.isPresent()) {
                return new GrantResult.GrantedByGroup(viaGroup.get());
            }
        }

        return new GrantResult.NotGranted();
    }

    private boolean matchesPath(RepoPermission perm, String path) {
        return matchesPattern(perm.getTarget(), perm.getValue(), perm.getMatchType(), path);
    }

    private boolean matchesPathRule(GroupPermissionRule rule, String path) {
        return matchesPattern(rule.getTarget(), rule.getValue(), rule.getMatchType(), path);
    }

    /**
     * Matches {@code value} against the part of {@code path} named by {@code target}. Without this, an {@code OWNER} or
     * {@code NAME} grant was always compared against the full {@code /owner/repo} slug and so never matched — the
     * target column landed in the model and stores (V5/V9) but the evaluator kept comparing the whole slug. Mirrors
     * {@code UrlRuleEvaluator.matchesRepo}, which already picks the subject per target on the URL-rule side.
     */
    private boolean matchesPattern(MatchTarget target, String value, MatchType matchType, String path) {
        String subject = subjectFor(target, path);
        return switch (matchType) {
            case LITERAL -> value.equals(subject);
            case GLOB -> matchesGlob(value, subject);
            case REGEX -> matchesRegex(value, subject);
        };
    }

    /**
     * Picks the comparison subject for a permission path. Permission paths use the {@code /owner/repo} convention, so
     * {@code OWNER} takes the first segment and {@code NAME} the second — split the same way
     * {@code RepositoryUrlRuleHook} derives owner/name from the server-mode slug, so permission and URL-rule matching
     * agree. A {@code null} target defaults to {@code SLUG} (the model default), matching the whole path.
     */
    private static String subjectFor(MatchTarget target, String path) {
        return switch (target == null ? MatchTarget.SLUG : target) {
            case SLUG -> path;
            case OWNER -> ownerOf(path);
            case NAME -> nameOf(path);
        };
    }

    /** First path segment of a {@code /owner/repo} slug, e.g. {@code "/myorg/repo"} → {@code "myorg"}. */
    private static String ownerOf(String path) {
        String[] parts = stripLeadingSlash(path).split("/", 3);
        return parts.length >= 1 ? parts[0] : "";
    }

    /** Second path segment of a {@code /owner/repo} slug, e.g. {@code "/myorg/repo"} → {@code "repo"}. */
    private static String nameOf(String path) {
        String[] parts = stripLeadingSlash(path).split("/", 3);
        return parts.length >= 2 ? parts[1] : "";
    }

    private static String stripLeadingSlash(String s) {
        if (s == null) return "";
        return s.startsWith("/") ? s.substring(1) : s;
    }

    private boolean matchesGlob(String pattern, String value) {
        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            return matcher.matches(Paths.get(value));
        } catch (Exception e) {
            log.warn("Invalid glob pattern '{}': {}", pattern, e.getMessage());
            return false;
        }
    }

    private boolean matchesRegex(String pattern, String value) {
        if (pattern.length() > MAX_REGEX_PATTERN_LENGTH) {
            log.warn(
                    "Refusing permission regex over {} chars ({} chars) - treating as no match",
                    MAX_REGEX_PATTERN_LENGTH,
                    pattern.length());
            return false;
        }
        try {
            return patternCache
                    .computeIfAbsent(pattern, Pattern::compile)
                    .matcher(new DeadlineCharSequence(value, REGEX_MATCH_TIMEOUT_MS))
                    .matches();
        } catch (PatternSyntaxException e) {
            log.warn("Invalid regex pattern '{}': {}", pattern, e.getMessage());
            return false;
        } catch (RegexTimeoutException e) {
            log.error(
                    "Permission regex '{}' exceeded {}ms matching '{}' (catastrophic backtracking?) - treating as no"
                            + " match",
                    pattern,
                    REGEX_MATCH_TIMEOUT_MS,
                    value);
            return false;
        }
    }

    /** Upper bound on a permission-rule regex; anything longer is refused rather than compiled. */
    private static final int MAX_REGEX_PATTERN_LENGTH = 1000;

    /** Wall-clock budget for one regex match. Repo slugs are short, so honest matches finish in microseconds. */
    private static final long REGEX_MATCH_TIMEOUT_MS = 100;

    /** Thrown by {@link DeadlineCharSequence} when a match runs past its deadline. */
    private static final class RegexTimeoutException extends RuntimeException {}

    /**
     * A {@link CharSequence} that aborts any regex match running past a deadline. {@code java.util.regex} calls
     * {@link #charAt} on every character access, including during backtracking, so a catastrophic-backtracking pattern
     * hits the deadline instead of hanging the permission check (and with it the push path).
     */
    private static final class DeadlineCharSequence implements CharSequence {
        private final CharSequence inner;
        private final long deadlineNanos;

        DeadlineCharSequence(CharSequence inner, long timeoutMs) {
            this(inner, System.nanoTime() + timeoutMs * 1_000_000L, true);
        }

        private DeadlineCharSequence(CharSequence inner, long deadlineNanos, boolean ignored) {
            this.inner = inner;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public int length() {
            return inner.length();
        }

        @Override
        public char charAt(int index) {
            if (System.nanoTime() > deadlineNanos) {
                throw new RegexTimeoutException();
            }
            return inner.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(inner.subSequence(start, end), deadlineNanos, true);
        }

        @Override
        public String toString() {
            return inner.toString();
        }
    }
}
