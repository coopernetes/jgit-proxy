package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyProviderServlet.GIT_REQUEST_ATTRIBUTE;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.service.UserAuthorizationService;

/**
 * Filter that validates a user has permission to push to a repository. This filter checks with the
 * UserAuthorizationService to determine if the user is authorized.
 */
@Slf4j
public class CheckUserPushPermissionFilter extends AbstractGitProxyFilter {

    private static final int ORDER = 200;
    private final UserAuthorizationService userAuthorizationService;

    public CheckUserPushPermissionFilter(UserAuthorizationService userAuthorizationService) {
        super(ORDER, Set.of(HttpOperation.PUSH));
        this.userAuthorizationService = userAuthorizationService;
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var requestDetails = (GitRequestDetails) request.getAttribute(GIT_REQUEST_ATTRIBUTE);
        if (requestDetails == null) {
            log.warn("GitRequestDetails not found in request attributes");
            return;
        }

        String userEmail = requestDetails.getUserEmail();
        if (userEmail == null || userEmail.isEmpty()) {
            log.warn("User email not found in request details");
            String errorMessage = "Push blocked: User not found. Please contact an administrator for support.";
            setResult(request, GitRequestDetails.GitResult.BLOCKED, "User not found");
            sendGitError(request, response, errorMessage);
            return;
        }

        // Check if user exists
        if (!userAuthorizationService.userExists(userEmail)) {
            log.warn("User {} does not exist in the system", userEmail);
            String errorMessage = String.format(
                    "Push blocked: User %s not found in the system. Please contact an administrator for support.",
                    userEmail);
            setResult(request, GitRequestDetails.GitResult.BLOCKED, "User does not exist");
            sendGitError(request, response, errorMessage);
            return;
        }

        // Get repository URL
        String repositoryUrl = getRepositoryUrl(requestDetails);

        // Check if user is authorized to push
        boolean isAuthorized = userAuthorizationService.isUserAuthorizedToPush(userEmail, repositoryUrl);

        if (!isAuthorized) {
            log.warn("User {} is not authorized to push to repository {}", userEmail, repositoryUrl);
            String errorMessage = String.format(
                    "Your push has been blocked (%s is not allowed to push on repo %s)", userEmail, repositoryUrl);
            setResult(request, GitRequestDetails.GitResult.BLOCKED, "User not authorized");
            sendGitError(request, response, errorMessage);
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
