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

    // Whitelist aggregate filters must be in the range 1000-1999
    private static final int MIN_WHITELIST_ORDER = 1000;
    private static final int MAX_WHITELIST_ORDER = 1999;

    public WhitelistAggregateFilter(
            int order,
            Set<HttpOperation> applicableOperations,
            GitProxyProvider provider,
            List<WhitelistByUrlFilter> whitelistFilters) {
        super(validateWhitelistOrder(order), applicableOperations, provider);
        this.whitelistFilters = whitelistFilters;
    }

    public WhitelistAggregateFilter(int order, GitProxyProvider provider, List<WhitelistByUrlFilter> whitelistFilters) {
        super(validateWhitelistOrder(order), DEFAULT_OPERATIONS, provider);
        this.whitelistFilters = whitelistFilters;
    }

    public WhitelistAggregateFilter(
            int order, GitProxyProvider provider, List<WhitelistByUrlFilter> whitelistFilters, String pathPrefix) {
        super(validateWhitelistOrder(order), DEFAULT_OPERATIONS, provider, pathPrefix);
        this.whitelistFilters = whitelistFilters;
    }

    /**
     * Validates that the whitelist filter order is within the allowed range.
     *
     * @param order The filter order
     * @return The validated order
     * @throws IllegalArgumentException if order is outside the allowed range
     */
    private static int validateWhitelistOrder(int order) {
        if (order < MIN_WHITELIST_ORDER || order > MAX_WHITELIST_ORDER) {
            throw new IllegalArgumentException(String.format(
                    "Whitelist filter order must be between %d and %d (inclusive), but was %d",
                    MIN_WHITELIST_ORDER, MAX_WHITELIST_ORDER, order));
        }
        return order;
    }

    @Override
    public String getStepName() {
        return "checkWhitelist";
    }

    @Override
    public boolean skipForRefDeletion() {
        return false; // Deletions must still be whitelisted
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response) throws IOException {
        for (WhitelistByUrlFilter filter : whitelistFilters) {
            filter.applyWhitelist(request);
        }
        String whitelistedBy = (String) request.getAttribute(WHITELISTED_BY_ATTRIBUTE);
        if (whitelistedBy != null) {
            log.debug("Whitelisted by {}", whitelistedBy);
        } else {
            var operation = determineOperation(request);
            String action = operation == HttpOperation.PUSH ? "Push" : "Fetch";
            String title = GitClient.SymbolCodes.NO_ENTRY.emoji() + "  " + action + " Blocked — Repository Not Allowed";
            String verb = operation == HttpOperation.PUSH ? "Pushes to" : "Fetches from";
            String message = verb + " this repository are not permitted.\n"
                    + "\n"
                    + "Contact an administrator to add this repository\n"
                    + "to the allowlist.";
            rejectAndSendError(
                    request,
                    response,
                    "Repository not in allowlist",
                    GitClient.formatForOperation(title, message, GitClient.AnsiColor.RED, operation));
        }
    }
}
