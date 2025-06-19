package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.git.GitClient.AnsiColor;
import static org.finos.gitproxy.git.GitClient.SymbolCodes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.git.GitClient;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpAuthScheme;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.provider.GitHubProvider;

/**
 * Filter that checks if the request is authenticated with a valid GitHub token. If the request is not authenticated, it
 * sends an error response to the client.
 */
@Slf4j
public class GitHubUserAuthenticatedFilter extends ProviderSpecificGitProxyFilter<GitHubProvider>
        implements AuthenticationRequiredFilter {

    private final Set<HttpAuthScheme> requiredAuthSchemes;

    private static final Set<HttpOperation> SUPPORTED_OPERATIONS = Set.of(HttpOperation.PUSH);
    private static final String REASON = "Missing required GitHub authentication";

    public GitHubUserAuthenticatedFilter(int order, GitHubProvider provider, Set<HttpAuthScheme> requiredAuthSchemes) {
        super(order, SUPPORTED_OPERATIONS, provider);
        this.requiredAuthSchemes = requiredAuthSchemes;
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!isAuthenticated(request) || !isUsingRequiredAuthScheme(request)) {
            setResult(request, GitRequestDetails.GitResult.BLOCKED, REASON);
            var op = determineOperation(request);
            String message = GitClient.formatForOperation(
                    SymbolCodes.NO_ENTRY.emoji() + "  Unauthorized! " + SymbolCodes.NO_ENTRY.emoji(),
                    "You must provide a valid personal access token for "
                            + provider.getUri().getHost(),
                    AnsiColor.RED,
                    op);
            sendGitError(request, response, message);
        }
    }

    @Override
    public boolean isAuthenticated(HttpServletRequest request) {
        String authValue = request.getHeader("Authorization");
        if (authValue == null) {
            return false;
        }
        var userDetails = provider.getRestClient().getUserInfo(authValue);
        return userDetails.isPresent();
    }

    @Override
    public boolean isUsingRequiredAuthScheme(HttpServletRequest request) {
        return requiredAuthSchemes.contains(getAuthScheme(request));
    }
}
