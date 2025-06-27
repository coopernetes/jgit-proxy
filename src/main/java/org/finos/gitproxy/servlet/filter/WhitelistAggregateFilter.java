package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.filter.WhitelistByUrlFilter.WHITELISTED_BY_ATTRIBUTE;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.git.GitClient;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.provider.GitProxyProvider;

/**
 * An aggregate filter that applies a list of {@link WhitelistByUrlFilter} filters to a given request and provider. This
 * filter is used to iterate through the list of whitelists and only block the request if it does not match any of the
 * whitelists.
 */
@Slf4j
@ToString
public class WhitelistAggregateFilter extends AbstractProviderAwareGitProxyFilter {

    private final List<WhitelistByUrlFilter> whitelistFilters;

    public WhitelistAggregateFilter(
            int order,
            Set<HttpOperation> applicableOperations,
            GitProxyProvider provider,
            List<WhitelistByUrlFilter> whitelistFilters) {
        super(order, applicableOperations, provider);
        this.whitelistFilters = whitelistFilters;
    }

    public WhitelistAggregateFilter(int order, GitProxyProvider provider, List<WhitelistByUrlFilter> whitelistFilters) {
        super(order, Set.of(HttpOperation.FETCH, HttpOperation.PUSH), provider);
        this.whitelistFilters = whitelistFilters;
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response) throws IOException {
        for (WhitelistByUrlFilter filter : whitelistFilters) {
            filter.applyWhitelist(request);
        }
        String whitelistedBy = (String) request.getAttribute(WHITELISTED_BY_ATTRIBUTE);
        if (whitelistedBy != null) {
            if (log.isDebugEnabled()) {
                log.debug("Whitelisted by {}", whitelistedBy);
            }
            setResult(request, GitRequestDetails.GitResult.ALLOWED, whitelistedBy);
        } else {
            setResult(request, GitRequestDetails.GitResult.BLOCKED, null);
            var operation = determineOperation(request);
            String title =
                    GitClient.SymbolCodes.NO_ENTRY.emoji() + " Disallowed! " + GitClient.SymbolCodes.NO_ENTRY.emoji();
            String action = operation == HttpOperation.PUSH ? "Pushes to" : "Fetches from";
            String message = action + " this repository are not permitted.";
            sendGitError(
                    request,
                    response,
                    GitClient.formatForOperation(title, message, GitClient.AnsiColor.RED, operation));
        }
    }
}
