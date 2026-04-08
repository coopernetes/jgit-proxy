package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.git.GitClientUtils.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClientUtils.sym;
import static org.finos.gitproxy.servlet.filter.UrlRuleFilter.DENIED_BY_ATTRIBUTE;
import static org.finos.gitproxy.servlet.filter.UrlRuleFilter.MATCHED_BY_ATTRIBUTE;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.db.FetchStore;
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

    // URL rule aggregate filters must be in the authorization range 50-199
    private static final int MIN_ORDER = 50;
    private static final int MAX_ORDER = 199;

    public UrlRuleAggregateFilter(
            int order,
            Set<HttpOperation> applicableOperations,
            GitProxyProvider provider,
            List<UrlRuleFilter> urlRuleFilters) {
        this(order, applicableOperations, provider, urlRuleFilters, null, null);
    }

    public UrlRuleAggregateFilter(int order, GitProxyProvider provider, List<UrlRuleFilter> urlRuleFilters) {
        this(order, DEFAULT_OPERATIONS, provider, urlRuleFilters, null, null);
    }

    public UrlRuleAggregateFilter(
            int order, GitProxyProvider provider, List<UrlRuleFilter> urlRuleFilters, String pathPrefix) {
        this(order, DEFAULT_OPERATIONS, provider, urlRuleFilters, pathPrefix, null);
    }

    public UrlRuleAggregateFilter(
            int order,
            GitProxyProvider provider,
            List<UrlRuleFilter> urlRuleFilters,
            String pathPrefix,
            FetchStore fetchStore) {
        this(order, DEFAULT_OPERATIONS, provider, urlRuleFilters, pathPrefix, fetchStore);
    }

    public UrlRuleAggregateFilter(
            int order,
            Set<HttpOperation> applicableOperations,
            GitProxyProvider provider,
            List<UrlRuleFilter> urlRuleFilters,
            String pathPrefix,
            FetchStore fetchStore) {
        super(validateOrder(order), applicableOperations, provider, pathPrefix != null ? pathPrefix : "");
        this.urlRuleFilters = urlRuleFilters;
        this.fetchStore = fetchStore;
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
        List<UrlRuleFilter> denyFilters = urlRuleFilters.stream()
                .filter(f -> f.getAccess() == AccessRule.Access.DENY)
                .toList();
        List<UrlRuleFilter> allowFilters = urlRuleFilters.stream()
                .filter(f -> f.getAccess() == AccessRule.Access.ALLOW)
                .toList();

        // Deny rules evaluated first — deny overrides allow
        for (UrlRuleFilter filter : denyFilters) {
            filter.applyRule(request);
        }
        String deniedBy = (String) request.getAttribute(DENIED_BY_ATTRIBUTE);
        if (deniedBy != null) {
            log.debug("Blocked by deny rule: {}", deniedBy);
            var operation = determineOperation(request);
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
        String matchedBy = (String) request.getAttribute(MATCHED_BY_ATTRIBUTE);
        var operation = determineOperation(request);
        boolean allowed = matchedBy != null;

        if (allowed) {
            log.debug("Allowed: matched rule {}", matchedBy);
        }

        if (operation == HttpOperation.FETCH && fetchStore != null) {
            recordFetch(request, allowed);
        }

        if (!allowed) {
            String action = operation == HttpOperation.PUSH ? "Push" : "Fetch";
            String title = sym(NO_ENTRY) + "  " + action + " Blocked - Repository Not Allowed";
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
                    .provider(provider.getUri().getHost())
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
