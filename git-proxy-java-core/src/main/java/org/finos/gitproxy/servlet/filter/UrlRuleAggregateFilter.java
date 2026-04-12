package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.git.GitClientUtils.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClientUtils.sym;
import static org.finos.gitproxy.servlet.filter.UrlRuleFilter.DENIED_BY_ATTRIBUTE;
import static org.finos.gitproxy.servlet.filter.UrlRuleFilter.MATCHED_BY_ATTRIBUTE;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.db.FetchStore;
import org.finos.gitproxy.db.RepoRegistry;
import org.finos.gitproxy.db.model.AccessRule;
import org.finos.gitproxy.db.model.FetchRecord;
import org.finos.gitproxy.git.GitClientUtils;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.provider.GitProxyProvider;

/**
 * An aggregate filter that applies a list of {@link UrlRuleFilter} filters to a given request and provider. The request
 * is blocked only if it does not match any rule in the list.
 */
@Slf4j
@ToString
public class UrlRuleAggregateFilter extends AbstractProviderAwareGitProxyFilter {

    private final List<UrlRuleFilter> urlRuleFilters;
    private final FetchStore fetchStore;
    private final RepoRegistry repoRegistry;

    // URL rule aggregate filters must be in the authorization range 50-199
    private static final int MIN_ORDER = 50;
    private static final int MAX_ORDER = 199;
    private static final ConcurrentHashMap<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    public UrlRuleAggregateFilter(
            int order,
            Set<HttpOperation> applicableOperations,
            GitProxyProvider provider,
            List<UrlRuleFilter> urlRuleFilters) {
        this(order, applicableOperations, provider, urlRuleFilters, null, null, null);
    }

    public UrlRuleAggregateFilter(int order, GitProxyProvider provider, List<UrlRuleFilter> urlRuleFilters) {
        this(order, ALL_OPERATIONS, provider, urlRuleFilters, null, null, null);
    }

    public UrlRuleAggregateFilter(
            int order, GitProxyProvider provider, List<UrlRuleFilter> urlRuleFilters, String pathPrefix) {
        this(order, ALL_OPERATIONS, provider, urlRuleFilters, pathPrefix, null, null);
    }

    public UrlRuleAggregateFilter(
            int order,
            GitProxyProvider provider,
            List<UrlRuleFilter> urlRuleFilters,
            String pathPrefix,
            FetchStore fetchStore) {
        this(order, ALL_OPERATIONS, provider, urlRuleFilters, pathPrefix, fetchStore, null);
    }

    public UrlRuleAggregateFilter(
            int order,
            GitProxyProvider provider,
            List<UrlRuleFilter> urlRuleFilters,
            String pathPrefix,
            FetchStore fetchStore,
            RepoRegistry repoRegistry) {
        this(order, ALL_OPERATIONS, provider, urlRuleFilters, pathPrefix, fetchStore, repoRegistry);
    }

    public UrlRuleAggregateFilter(
            int order,
            Set<HttpOperation> applicableOperations,
            GitProxyProvider provider,
            List<UrlRuleFilter> urlRuleFilters,
            String pathPrefix,
            FetchStore fetchStore) {
        this(order, applicableOperations, provider, urlRuleFilters, pathPrefix, fetchStore, null);
    }

    public UrlRuleAggregateFilter(
            int order,
            Set<HttpOperation> applicableOperations,
            GitProxyProvider provider,
            List<UrlRuleFilter> urlRuleFilters,
            String pathPrefix,
            FetchStore fetchStore,
            RepoRegistry repoRegistry) {
        super(validateOrder(order), applicableOperations, provider, pathPrefix != null ? pathPrefix : "");
        this.urlRuleFilters = urlRuleFilters;
        this.fetchStore = fetchStore;
        this.repoRegistry = repoRegistry;
    }

    private static int validateOrder(int order) {
        if (order < MIN_ORDER || order > MAX_ORDER) {
            throw new IllegalArgumentException(String.format(
                    "UrlRuleAggregateFilter order must be in the authorization range %d-%d (inclusive), but was %d",
                    MIN_ORDER, MAX_ORDER, order));
        }
        return order;
    }

    @Override
    public String getStepName() {
        return "checkUrlRules";
    }

    @Override
    public boolean skipForRefDeletion() {
        return false; // Deletions must still match an allow rule
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var operation = determineOperation(request);

        if (operation == HttpOperation.INFO) {
            applyInfoRefsRules(request, response);
            return;
        }

        var details =
                (GitRequestDetails) request.getAttribute(org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR);

        List<UrlRuleFilter> denyFilters = urlRuleFilters.stream()
                .filter(f -> f.getAccess() == AccessRule.Access.DENY)
                .filter(f -> f.appliesTo(operation))
                .toList();
        List<UrlRuleFilter> allowFilters = urlRuleFilters.stream()
                .filter(f -> f.getAccess() == AccessRule.Access.ALLOW)
                .filter(f -> f.appliesTo(operation))
                .toList();

        // Deny rules evaluated first — deny overrides allow
        for (UrlRuleFilter filter : denyFilters) {
            filter.applyRule(request);
        }
        // Also evaluate DB deny rules
        if (repoRegistry != null && details != null && request.getAttribute(DENIED_BY_ATTRIBUTE) == null) {
            List<AccessRule> dbRules = repoRegistry.findEnabledForProvider(provider.getProviderId());
            for (AccessRule rule : dbRules) {
                if (rule.getAccess() == AccessRule.Access.DENY
                        && matchesDbRule(rule, details.getRepoRef(), operation)) {
                    request.setAttribute(DENIED_BY_ATTRIBUTE, rule.getId());
                    log.debug("Blocked by DB deny rule: id={}", rule.getId());
                    break;
                }
            }
        }

        String deniedBy = (String) request.getAttribute(DENIED_BY_ATTRIBUTE);
        if (deniedBy != null) {
            log.debug("Blocked by deny rule: {}", deniedBy);
            if (operation == HttpOperation.FETCH && fetchStore != null) {
                recordFetch(request, false);
            }
            String action = operation == HttpOperation.PUSH ? "Push" : "Fetch";
            String title = sym(NO_ENTRY) + "  " + action + " Blocked - Repository Denied";
            String verb = operation == HttpOperation.PUSH ? "Pushes to" : "Fetches from";
            String message = verb + " this repository are not permitted.\n"
                    + "\n"
                    + "This repository has been explicitly denied by an administrator.";
            rejectAndSendError(
                    request,
                    response,
                    "Repository blocked by deny rule",
                    GitClientUtils.formatForOperation(title, message, GitClientUtils.AnsiColor.RED, operation));
            return;
        }

        // Allow rules evaluated next — must match at least one allow rule to proceed
        for (UrlRuleFilter filter : allowFilters) {
            filter.applyRule(request);
        }
        // Also evaluate DB allow rules
        if (repoRegistry != null && details != null && request.getAttribute(MATCHED_BY_ATTRIBUTE) == null) {
            List<AccessRule> dbRules = repoRegistry.findEnabledForProvider(provider.getProviderId());
            for (AccessRule rule : dbRules) {
                if (rule.getAccess() == AccessRule.Access.ALLOW
                        && matchesDbRule(rule, details.getRepoRef(), operation)) {
                    request.setAttribute(MATCHED_BY_ATTRIBUTE, rule.getId());
                    log.debug("Allowed by DB allow rule: id={}", rule.getId());
                    break;
                }
            }
        }

        String matchedBy = (String) request.getAttribute(MATCHED_BY_ATTRIBUTE);
        boolean allowed = matchedBy != null;

        if (allowed) {
            log.debug("Allowed: matched rule {}", matchedBy);
        }

        if (operation == HttpOperation.FETCH && fetchStore != null) {
            recordFetch(request, allowed);
        }

        if (!allowed) {
            String action = operation == HttpOperation.PUSH ? "Push" : "Fetch";
            String title = action + " blocked - Repository Not Allowed";
            String verb = operation == HttpOperation.PUSH ? "Pushes to" : "Fetches from";
            String message = verb + " this repository are not permitted.\n"
                    + "\n"
                    + "Contact an administrator to add this repository\n"
                    + "to the allow rules.";
            rejectAndSendError(
                    request,
                    response,
                    "Repository not in allow rules",
                    GitClientUtils.formatForOperation(title, message, GitClientUtils.AnsiColor.RED, operation));
        }
    }

    /**
     * Returns true if the given {@link AccessRule} matches the repo reference and operation. Slug/owner/name
     * comparisons strip any leading {@code /} so that stored values like {@code coopernetes/repo} and
     * {@code /coopernetes/repo} both match the request regardless of how the rule was saved.
     */
    static boolean matchesDbRule(AccessRule rule, GitRequestDetails.RepoRef ref, HttpOperation operation) {
        // Filter by operation
        if (rule.getOperations() == AccessRule.Operations.PUSH && operation == HttpOperation.FETCH) return false;
        if (rule.getOperations() == AccessRule.Operations.FETCH && operation == HttpOperation.PUSH) return false;

        // Match by whichever field is set (slug takes priority, then owner, then name)
        if (rule.getSlug() != null) {
            return matchPattern(stripLeadingSlash(rule.getSlug()), stripLeadingSlash(ref.getSlug()));
        }
        if (rule.getOwner() != null) {
            return matchPattern(stripLeadingSlash(rule.getOwner()), ref.getOwner());
        }
        if (rule.getName() != null) {
            return matchPattern(stripLeadingSlash(rule.getName()), ref.getName());
        }
        return false;
    }

    private static String stripLeadingSlash(String s) {
        return (s != null && s.startsWith("/")) ? s.substring(1) : s;
    }

    private static boolean matchPattern(String pattern, String value) {
        if (pattern == null || value == null) return false;
        if (pattern.equals(value)) return true;
        if (pattern.startsWith("regex:")) {
            return PATTERN_CACHE
                    .computeIfAbsent(pattern.substring(6), Pattern::compile)
                    .matcher(value)
                    .matches();
        }
        if (pattern.contains("*") || pattern.contains("?") || pattern.contains("[")) {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            return matcher.matches(Paths.get(value));
        }
        return false;
    }

    /**
     * Applies URL allow/deny rules to an {@code /info/refs} discovery request. The effective operation (FETCH or PUSH)
     * is derived from the {@code service} query parameter. When blocked, responds with the provider-configured HTTP
     * status (default 403) rather than a git-protocol error message, since {@code /info/refs} is a plain HTTP exchange.
     */
    private void applyInfoRefsRules(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String service = request.getParameter("service");
        HttpOperation effectiveOp;
        if ("git-upload-pack".equals(service)) {
            effectiveOp = HttpOperation.FETCH;
        } else if ("git-receive-pack".equals(service)) {
            effectiveOp = HttpOperation.PUSH;
        } else {
            // Unrecognised service — pass through and let the upstream handle it
            return;
        }

        var details =
                (GitRequestDetails) request.getAttribute(org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR);

        // Deny rules first
        for (UrlRuleFilter filter : urlRuleFilters.stream()
                .filter(f -> f.getAccess() == AccessRule.Access.DENY)
                .filter(f -> f.appliesTo(effectiveOp))
                .toList()) {
            filter.applyRule(request);
        }
        if (repoRegistry != null && details != null && request.getAttribute(DENIED_BY_ATTRIBUTE) == null) {
            for (AccessRule rule : repoRegistry.findEnabledForProvider(provider.getProviderId())) {
                if (rule.getAccess() == AccessRule.Access.DENY
                        && matchesDbRule(rule, details.getRepoRef(), effectiveOp)) {
                    request.setAttribute(DENIED_BY_ATTRIBUTE, rule.getId());
                    break;
                }
            }
        }

        if (request.getAttribute(DENIED_BY_ATTRIBUTE) != null) {
            log.debug("Blocking /info/refs — matched deny rule: {}", request.getAttribute(DENIED_BY_ATTRIBUTE));
            setResult(request, GitRequestDetails.GitResult.REJECTED, "Repository blocked by deny rule");
            response.sendError(provider.getBlockedInfoRefsStatus());
            return;
        }

        // Allow rules
        for (UrlRuleFilter filter : urlRuleFilters.stream()
                .filter(f -> f.getAccess() == AccessRule.Access.ALLOW)
                .filter(f -> f.appliesTo(effectiveOp))
                .toList()) {
            filter.applyRule(request);
        }
        if (repoRegistry != null && details != null && request.getAttribute(MATCHED_BY_ATTRIBUTE) == null) {
            for (AccessRule rule : repoRegistry.findEnabledForProvider(provider.getProviderId())) {
                if (rule.getAccess() == AccessRule.Access.ALLOW
                        && matchesDbRule(rule, details.getRepoRef(), effectiveOp)) {
                    request.setAttribute(MATCHED_BY_ATTRIBUTE, rule.getId());
                    break;
                }
            }
        }

        if (request.getAttribute(MATCHED_BY_ATTRIBUTE) == null) {
            log.debug("Blocking /info/refs — no allow rule matched");
            setResult(request, GitRequestDetails.GitResult.REJECTED, "Repository not in allow rules");
            response.sendError(provider.getBlockedInfoRefsStatus());
        }
    }

    private void recordFetch(HttpServletRequest request, boolean allowed) {
        try {
            var details = (GitRequestDetails)
                    request.getAttribute(org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR);
            if (details == null) return;
            var ref = details.getRepoRef();
            String authHeader = request.getHeader("Authorization");
            String pushUsername = null;
            if (authHeader != null && authHeader.startsWith("Basic ")) {
                String decoded = new String(java.util.Base64.getDecoder().decode(authHeader.substring(6)));
                int colon = decoded.indexOf(':');
                if (colon > 0) pushUsername = decoded.substring(0, colon);
            }
            fetchStore.record(FetchRecord.builder()
                    .provider(provider.getProviderId())
                    .owner(ref.getOwner())
                    .repoName(ref.getName())
                    .result(allowed ? FetchRecord.Result.ALLOWED : FetchRecord.Result.BLOCKED)
                    .pushUsername(pushUsername)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to record fetch event", e);
        }
    }
}
