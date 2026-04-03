package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.git.GitClient.AnsiColor.*;
import static org.finos.gitproxy.git.GitClient.SymbolCodes.*;
import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.git.GitClient;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.service.UserAuthorizationService;

/**
 * Filter that validates a user has permission to push to a repository. This filter checks with the
 * UserAuthorizationService to determine if the user is authorized.
 *
 * <p>This filter runs at order 2000, which is in the built-in content filters range (2000-4999).
 */
@Slf4j
public class CheckUserPushPermissionFilter extends AbstractGitProxyFilter {

    private static final int ORDER = 2000;
    private final UserAuthorizationService userAuthorizationService;

    public CheckUserPushPermissionFilter(UserAuthorizationService userAuthorizationService) {
        super(ORDER, Set.of(HttpOperation.PUSH));
        this.userAuthorizationService = userAuthorizationService;
    }

    @Override
    public String getStepName() {
        return "checkUserPermission";
    }

    @Override
    public boolean skipForRefDeletion() {
        return false; // Permission check still applies to deletions
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var requestDetails = (GitRequestDetails) request.getAttribute(GIT_REQUEST_ATTR);
        if (requestDetails == null) {
            log.warn("GitRequestDetails not found in request attributes");
            return;
        }

        // Branch deletions send commitTo = 0000...0 (zero SHA) with no new commits.
        // There is no author to extract identity from; the user is already authenticated
        // by HTTP basic auth at the transport layer, so let deletions pass through.
        String commitTo = requestDetails.getCommitTo();
        if (commitTo != null && commitTo.matches("^0+$")) {
            log.debug("Branch deletion detected (commitTo={}), skipping user permission check", commitTo);
            return;
        }

        // Extract user email from commit author
        String userEmail = null;
        if (requestDetails.getCommit() != null && requestDetails.getCommit().getAuthor() != null) {
            userEmail = requestDetails.getCommit().getAuthor().getEmail();
        }

        if (userEmail == null || userEmail.isEmpty()) {
            log.warn("User email not found in commit author");
            String title = NO_ENTRY.emoji() + "  Push Blocked — Unknown User";
            String message = "Could not identify the pushing user.\n" + "\n" + "Contact an administrator for support.";
            rejectAndSendError(request, response, "User not found", GitClient.format(title, message, RED, null));
            return;
        }

        // Check if user exists
        if (!userAuthorizationService.userExists(userEmail)) {
            log.warn("User {} does not exist in the system", userEmail);
            String title = NO_ENTRY.emoji() + "  Push Blocked — User Not Registered";
            String message = CROSS_MARK.emoji() + "  " + userEmail + " is not registered.\n"
                    + "\n"
                    + "Contact an administrator for support.";
            rejectAndSendError(request, response, "User does not exist", GitClient.format(title, message, RED, null));
            return;
        }

        // Get repository URL
        String repositoryUrl = getRepositoryUrl(requestDetails);

        // Check if user is authorized to push
        boolean isAuthorized = userAuthorizationService.isUserAuthorizedToPush(userEmail, repositoryUrl);

        if (!isAuthorized) {
            log.warn("User {} is not authorized to push to repository {}", userEmail, repositoryUrl);
            String title = NO_ENTRY.emoji() + "  Push Blocked — Unauthorized";
            String message = CROSS_MARK.emoji() + "  " + userEmail + " is not allowed to push to:\n" + "   "
                    + LINK.emoji() + "  " + repositoryUrl;
            rejectAndSendError(request, response, "User not authorized", GitClient.format(title, message, RED, null));
            return;
        }

        log.debug("User {} is authorized to push to repository {}", userEmail, repositoryUrl);
    }

    /**
     * Construct the repository URL from request details.
     *
     * @param requestDetails The request details
     * @return The repository URL
     */
    private String getRepositoryUrl(GitRequestDetails requestDetails) {
        if (requestDetails.getProvider() != null) {
            String providerName = requestDetails.getProvider().getName();
            String slug = requestDetails.getRepository().getSlug();
            return String.format("https://%s/%s", providerName, slug);
        }
        return requestDetails.getRepository().getSlug();
    }
}
