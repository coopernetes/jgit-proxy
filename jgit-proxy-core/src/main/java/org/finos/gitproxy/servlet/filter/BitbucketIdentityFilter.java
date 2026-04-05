package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.provider.BitbucketProvider;
import org.finos.gitproxy.provider.client.ScmUserInfo;

/**
 * Transparent-proxy filter that resolves the Bitbucket push username and stores it on {@link GitRequestDetails} for
 * outbound credential rewriting.
 *
 * <p>Bitbucket does not validate the HTTP Basic-auth username on git pushes — only the token is checked. The proxy
 * convention is that the username field carries the user's Bitbucket account email. This filter calls {@code GET
 * /2.0/user} with {@code Basic email:token} auth to retrieve the auto-generated {@code username} field that Bitbucket
 * recognises for git authentication, and stores it as {@link GitRequestDetails#getUpstreamUsername()}.
 *
 * <p>{@link PushFinalizerFilter} reads that field and rewrites the outbound {@code Authorization} header from
 * {@code Basic(email:token)} to {@code Basic(username:token)} before forwarding the push to Bitbucket.
 *
 * <p>Runs at order 148, before {@link CheckUserPushPermissionFilter} (150) so that the upstream username is available
 * throughout the rest of the chain if needed.
 */
@Slf4j
public class BitbucketIdentityFilter extends ProviderSpecificGitProxyFilter<BitbucketProvider> {

    private static final int ORDER = 148;

    public BitbucketIdentityFilter(BitbucketProvider provider) {
        super(ORDER, Set.of(HttpOperation.PUSH), provider);
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var details = (GitRequestDetails) request.getAttribute(GIT_REQUEST_ATTR);
        if (details == null) {
            return;
        }

        String[] userPass = extractBasicAuth(request);
        if (userPass == null) {
            log.debug("No Basic auth credentials — skipping Bitbucket upstream username resolution");
            return;
        }

        String pushEmail = userPass[0];
        String token = userPass[1];

        Optional<ScmUserInfo> identity = provider.fetchScmIdentity(pushEmail, token);
        if (identity.isEmpty()) {
            log.debug(
                    "Could not resolve Bitbucket upstream username for '{}' — credentials may be reused as-is",
                    pushEmail);
            return;
        }

        String upstreamUsername = identity.get().login();
        details.setUpstreamUsername(upstreamUsername);
        log.debug("Resolved Bitbucket upstream username '{}' for push email '{}'", upstreamUsername, pushEmail);
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
