package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.git.GitClientUtils.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClientUtils.sym;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.db.FetchStore;
import org.finos.gitproxy.db.RepoRegistry;
import org.finos.gitproxy.db.model.FetchRecord;
import org.finos.gitproxy.git.GitClientUtils;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.provider.GitProxyProvider;

/**
 * HTTP adapter that applies URL allow/deny rules to git proxy requests. Rule evaluation is delegated entirely to
 * {@link UrlRuleEvaluator}; this class only handles extracting the request context and writing the HTTP response.
 *
 * <p>For push/fetch operations: evaluates rules and either passes the request down the chain or sends a git-protocol
 * error response. For {@code /info/refs} discovery: evaluates rules and sends an HTTP status error (default 403).
 */
@Slf4j
@ToString
public class UrlRuleAggregateFilter extends AbstractProviderAwareGitProxyFilter {

    private final UrlRuleEvaluator evaluator;
    private final FetchStore fetchStore;

    // URL rule aggregate filters must be in the authorization range 50-199
    private static final int MIN_ORDER = 50;
    private static final int MAX_ORDER = 199;

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
        this.evaluator = new UrlRuleEvaluator(urlRuleFilters, repoRegistry, provider);
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
        var operation = determineOperation(request);

        if (operation == HttpOperation.INFO) {
            applyInfoRefsRules(request, response);
            return;
        }

        var details =
                (GitRequestDetails) request.getAttribute(org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR);
        String slug = details != null ? details.getRepoRef().getSlug() : null;
        String owner = details != null ? details.getRepoRef().getOwner() : null;
        String name = details != null ? details.getRepoRef().getName() : null;

        UrlRuleEvaluator.Result result = evaluator.evaluate(slug, owner, name, operation);

        switch (result) {
            case UrlRuleEvaluator.Result.Denied d -> {
                log.debug("Blocked by deny rule: {}", d.ruleId());
                if (operation == HttpOperation.FETCH && fetchStore != null) recordFetch(request, false);
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
            }
            case UrlRuleEvaluator.Result.Allowed a -> {
                log.debug("Allowed by rule: {}", a.ruleId());
                if (operation == HttpOperation.FETCH && fetchStore != null) recordFetch(request, true);
            }
            // Proxy mode is always fail-closed: no matching allow rule → block, regardless of whether
            // allow rules are absent (OpenMode) or present but unmatched (NotAllowed).
            case UrlRuleEvaluator.Result.OpenMode m -> {
                log.debug("Blocked — no allow rules configured");
                if (operation == HttpOperation.FETCH && fetchStore != null) recordFetch(request, false);
                sendNotAllowed(request, response, operation);
            }
            case UrlRuleEvaluator.Result.NotAllowed n -> {
                log.debug("Blocked — no allow rule matched");
                if (operation == HttpOperation.FETCH && fetchStore != null) recordFetch(request, false);
                sendNotAllowed(request, response, operation);
            }
        }
    }

    private void sendNotAllowed(HttpServletRequest request, HttpServletResponse response, HttpOperation operation)
            throws java.io.IOException {
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

    /**
     * Applies URL allow/deny rules to an {@code /info/refs} discovery request. The effective operation (FETCH or PUSH)
     * is derived from the {@code service} query parameter. When blocked, responds with the provider-configured HTTP
     * status (default 403).
     */
    private void applyInfoRefsRules(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String service = request.getParameter("service");
        HttpOperation effectiveOp =
                switch (service) {
                    case "git-upload-pack" -> HttpOperation.FETCH;
                    case "git-receive-pack" -> HttpOperation.PUSH;
                    default -> null; // unrecognised service — pass through
                };
        if (effectiveOp == null) return;

        var details =
                (GitRequestDetails) request.getAttribute(org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR);
        String slug = details != null ? details.getRepoRef().getSlug() : null;
        String owner = details != null ? details.getRepoRef().getOwner() : null;
        String name = details != null ? details.getRepoRef().getName() : null;

        UrlRuleEvaluator.Result result = evaluator.evaluate(slug, owner, name, effectiveOp);

        switch (result) {
            case UrlRuleEvaluator.Result.Denied d -> {
                log.debug("Blocking /info/refs — matched deny rule: {}", d.ruleId());
                setResult(request, GitRequestDetails.GitResult.REJECTED, "Repository blocked by deny rule");
                response.sendError(provider.getBlockedInfoRefsStatus());
            }
            case UrlRuleEvaluator.Result.OpenMode m -> {
                log.debug("Blocking /info/refs — no allow rules configured");
                setResult(request, GitRequestDetails.GitResult.REJECTED, "Repository not in allow rules");
                response.sendError(provider.getBlockedInfoRefsStatus());
            }
            case UrlRuleEvaluator.Result.NotAllowed n -> {
                log.debug("Blocking /info/refs — no allow rule matched");
                setResult(request, GitRequestDetails.GitResult.REJECTED, "Repository not in allow rules");
                response.sendError(provider.getBlockedInfoRefsStatus());
            }
            case UrlRuleEvaluator.Result.Allowed a -> {
                /* pass through — request is permitted */
            }
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
