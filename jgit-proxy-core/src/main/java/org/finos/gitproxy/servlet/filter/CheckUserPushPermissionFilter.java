package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.git.GitClientUtils.AnsiColor.*;
import static org.finos.gitproxy.git.GitClientUtils.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClientUtils.sym;
import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;
import static org.finos.gitproxy.servlet.GitProxyServlet.SERVICE_URL_ATTR;

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
import org.finos.gitproxy.permission.RepoPermissionService;
import org.finos.gitproxy.service.PushIdentityResolver;
import org.finos.gitproxy.user.UserEntry;

/**
 * Filter that validates a user has permission to push to a repository.
 *
 * <p>Fail-closed: if no permission grants exist for the requested repository the push is denied. A {@code null}
 * {@link PushIdentityResolver} means open mode (no user store configured) and skips all checks.
 *
 * <p>This filter runs at order 150, which is in the authorization range (0-199).
 */
@Slf4j
public class CheckUserPushPermissionFilter extends AbstractGitProxyFilter {

    private static final int ORDER = 150;
    private final PushIdentityResolver identityResolver;
    private final RepoPermissionService repoPermissionService;

    public CheckUserPushPermissionFilter(
            PushIdentityResolver identityResolver, RepoPermissionService repoPermissionService) {
        super(ORDER, Set.of(HttpOperation.PUSH));
        this.identityResolver = identityResolver;
        this.repoPermissionService = repoPermissionService;
    }

    @Override
    public String getStepName() {
        return "checkUserPermission";
    }

    @Override
    public boolean skipForRefDeletion() {
        return false;
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

        String[] userPass = extractBasicAuth(request);
        String pushUsername = userPass != null ? userPass[0] : null;
        String pushToken = userPass != null ? userPass[1] : null;

        Optional<UserEntry> resolved = identityResolver.resolve(requestDetails.getProvider(), pushUsername, pushToken);

        if (resolved.isEmpty()) {
            String identity = pushUsername != null ? pushUsername : "(unknown)";
            log.warn("Push user '{}' could not be resolved to a registered proxy user", identity);
            String providerName = requestDetails.getProvider() != null
                    ? requestDetails.getProvider().getName()
                    : "SCM";
            String serviceUrl = (String) request.getAttribute(SERVICE_URL_ATTR);
            String profileHint = serviceUrl != null
                    ? "Link your " + providerName + " identity at:\n  " + sym(LINK) + "  " + serviceUrl + "/profile"
                    : "Ask an administrator to link your " + providerName + " identity to your proxy account.";
            String title = sym(NO_ENTRY) + "  Push Blocked - Identity Not Linked";
            String message = sym(CROSS_MARK) + "  Your " + providerName
                    + " credentials could not be matched to a proxy account.\n\n" + profileHint;
            rejectAndSendError(
                    request, response, "Identity not linked", GitClientUtils.format(title, message, RED, null));
            return;
        }

        UserEntry user = resolved.get();
        String providerName = requestDetails.getProvider() != null
                ? requestDetails.getProvider().getName()
                : null;
        String slug = requestDetails.getRepoRef() != null
                ? requestDetails.getRepoRef().getSlug()
                : null;

        if (providerName == null
                || slug == null
                || !repoPermissionService.isAllowedToPush(user.getUsername(), providerName, slug)) {
            log.warn(
                    "Push user '{}' (resolved as '{}') is not authorized to push to {}/{}",
                    pushUsername,
                    user.getUsername(),
                    providerName,
                    slug);
            String repoUrl =
                    providerName != null && slug != null ? String.format("https://%s%s", providerName, slug) : slug;
            String title = sym(NO_ENTRY) + "  Push Blocked - Unauthorized";
            String message = sym(CROSS_MARK) + "  " + user.getUsername() + " is not allowed to push to:\n" + "   "
                    + sym(LINK) + "  " + repoUrl;
            rejectAndSendError(
                    request, response, "User not authorized", GitClientUtils.format(title, message, RED, null));
            return;
        }

        log.debug(
                "Push user '{}' resolved as '{}' and authorized for {}/{}",
                pushUsername,
                user.getUsername(),
                providerName,
                slug);
        requestDetails.setResolvedUser(user.getUsername());
        if (requestDetails.getProvider() != null && user.getScmIdentities() != null) {
            user.getScmIdentities().stream()
                    .filter(id -> requestDetails.getProvider().getName().equalsIgnoreCase(id.getProvider()))
                    .map(org.finos.gitproxy.user.ScmIdentity::getUsername)
                    .findFirst()
                    .ifPresent(requestDetails::setScmUsername);
        }
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
