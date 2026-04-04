package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.git.GitClientUtils.AnsiColor.*;
import static org.finos.gitproxy.git.GitClientUtils.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClientUtils.sym;
import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.git.GitClientUtils;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.service.PushIdentityResolver;
import org.finos.gitproxy.service.UserAuthorizationService;
import org.finos.gitproxy.user.UserEntry;

/**
 * Filter that validates a user has permission to push to a repository. This filter checks with the
 * UserAuthorizationService to determine if the user is authorized.
 *
 * <p>This filter runs at order 150, which is in the authorization range (0-199).
 */
@Slf4j
public class CheckUserPushPermissionFilter extends AbstractGitProxyFilter {

    private static final int ORDER = 150;
    private final PushIdentityResolver identityResolver;
    private final UserAuthorizationService userAuthorizationService;

    public CheckUserPushPermissionFilter(
            PushIdentityResolver identityResolver, UserAuthorizationService userAuthorizationService) {
        super(ORDER, Set.of(HttpOperation.PUSH));
        this.identityResolver = identityResolver;
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

        // No resolver means open mode — no user store configured, skip the check.
        if (identityResolver == null) {
            log.debug("No PushIdentityResolver configured — skipping push permission check (open mode)");
            return;
        }

        // Resolve identity from HTTP Basic-auth credentials (provider + username + token).
        // We do NOT use commit author email — it's unverifiable and not provided by the proxy user.
        String[] userPass = extractBasicAuth(request);
        String pushUsername = userPass != null ? userPass[0] : null;
        String pushToken = userPass != null ? userPass[1] : null;
        String provider = requestDetails.getProvider() != null
                ? requestDetails.getProvider().getName()
                : "";

        Optional<UserEntry> resolved = identityResolver.resolve(provider, pushUsername, pushToken);

        if (resolved.isEmpty()) {
            String identity = pushUsername != null ? pushUsername : "(unknown)";
            log.warn("Push user '{}' could not be resolved to a registered proxy user", identity);
            String title = sym(NO_ENTRY) + "  Push Blocked - User Not Registered";
            String message = sym(CROSS_MARK) + "  " + identity + " is not registered.\n"
                    + "\n"
                    + "Contact an administrator for support.";
            rejectAndSendError(
                    request, response, "User does not exist", GitClientUtils.format(title, message, RED, null));
            return;
        }

        UserEntry user = resolved.get();
        String repositoryUrl = getRepositoryUrl(requestDetails);

        if (!userAuthorizationService.isUserAuthorizedToPush(user.getUsername(), repositoryUrl)) {
            log.warn(
                    "Push user '{}' (resolved as '{}') is not authorized to push to {}",
                    pushUsername,
                    user.getUsername(),
                    repositoryUrl);
            String title = sym(NO_ENTRY) + "  Push Blocked - Unauthorized";
            String message = sym(CROSS_MARK) + "  " + user.getUsername() + " is not allowed to push to:\n" + "   "
                    + sym(LINK) + "  " + repositoryUrl;
            rejectAndSendError(
                    request, response, "User not authorized", GitClientUtils.format(title, message, RED, null));
            return;
        }

        log.debug(
                "Push user '{}' resolved as '{}' and authorized for {}",
                pushUsername,
                user.getUsername(),
                repositoryUrl);
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
            String slug = requestDetails.getRepoRef().getSlug();
            return String.format("https://%s/%s", providerName, slug);
        }
        return requestDetails.getRepoRef().getSlug();
    }

    private static String[] extractBasicAuth(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Basic ")) return null;
        try {
            String decoded = new String(Base64.getDecoder()
                    .decode(authHeader.substring("Basic ".length()).trim()));
            int colon = decoded.indexOf(':');
            if (colon < 0) return null;
            return new String[] {decoded.substring(0, colon), decoded.substring(colon + 1)};
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
