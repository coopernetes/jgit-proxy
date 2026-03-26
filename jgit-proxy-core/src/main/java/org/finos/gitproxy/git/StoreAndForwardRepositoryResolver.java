package org.finos.gitproxy.git;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.finos.gitproxy.provider.GitProxyProvider;

/**
 * Repository resolver for store-and-forward mode. Syncs a local bare repo from the upstream provider on each open,
 * ensuring the local mirror is fresh for both fetch and push operations.
 *
 * <p>Stores the clean upstream URL (without credentials) in the repository config under {@code gitproxy.upstreamUrl} so
 * that {@link ForwardingPostReceiveHook} can read it later to know where to push.
 *
 * <p>Also stores extracted credentials as a request attribute ({@link #CREDENTIALS_ATTRIBUTE}) so that
 * {@link StoreAndForwardReceivePackFactory} can pass them to the forwarding hook.
 */
@Slf4j
@RequiredArgsConstructor
public class StoreAndForwardRepositoryResolver implements RepositoryResolver<HttpServletRequest> {

    public static final String CREDENTIALS_ATTRIBUTE = "org.finos.gitproxy.credentials";

    private final LocalRepositoryCache cache;
    private final GitProxyProvider provider;

    @Override
    public Repository open(HttpServletRequest req, String name)
            throws RepositoryNotFoundException, ServiceNotAuthorizedException, ServiceNotEnabledException,
                    ServiceMayNotContinueException {

        // JGit passes name as the path after the servlet mapping, e.g. "owner/repo.git"
        String cleanName = name.replaceAll("\\.git$", "");

        // Extract credentials from Basic auth header or URL userinfo
        String[] userPass = extractCredentials(req);

        // Construct URLs
        String cleanUpstreamUrl = provider.getUri() + "/" + cleanName + ".git";
        String authenticatedUrl = buildAuthenticatedUrl(userPass, cleanName);

        // Store credentials as request attribute for ReceivePackFactory to pick up
        if (userPass != null) {
            CredentialsProvider creds = new UsernamePasswordCredentialsProvider(userPass[0], userPass[1]);
            req.setAttribute(CREDENTIALS_ATTRIBUTE, creds);
        }

        log.info("Opening store-and-forward repository: {} -> {}", name, cleanUpstreamUrl);

        try {
            // Clone or fetch from upstream (using authenticated URL for private repos)
            Repository repo = cache.getOrClone(authenticatedUrl);

            // Store the clean upstream URL so PostReceiveHook can find it
            repo.getConfig().setString("gitproxy", null, "upstreamUrl", cleanUpstreamUrl);
            repo.getConfig().save();

            return repo;
        } catch (Exception e) {
            log.error("Failed to open repository: {} from upstream {}", name, cleanUpstreamUrl, e);
            throw new RepositoryNotFoundException(name, e);
        }
    }

    /**
     * Extract credentials from either the Authorization header or the URL userinfo. Git clients typically only send
     * Basic auth after a 401 challenge, but when credentials are embedded in the remote URL (e.g.
     * {@code http://user:token@host/...}), they appear in the Authorization header on the initial request for some
     * clients, or not at all for others. This method tries both sources.
     */
    private String[] extractCredentials(HttpServletRequest req) {
        // Try Authorization header first
        String authHeader = req.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Basic ")) {
            try {
                String base64 = authHeader.substring("Basic ".length()).trim();
                String decoded = new String(Base64.getDecoder().decode(base64));
                int colon = decoded.indexOf(':');
                if (colon >= 0) {
                    return new String[] {decoded.substring(0, colon), decoded.substring(colon + 1)};
                }
            } catch (IllegalArgumentException e) {
                log.warn("Invalid Base64 in Authorization header", e);
            }
        }

        // Fall back to URL userinfo — git embeds user:pass in the request URL
        // The servlet container exposes this via the request URL
        String requestUrl = req.getRequestURL().toString();
        try {
            java.net.URI uri = java.net.URI.create(requestUrl);
            String userInfo = uri.getUserInfo();
            if (userInfo != null) {
                int colon = userInfo.indexOf(':');
                if (colon >= 0) {
                    return new String[] {userInfo.substring(0, colon), userInfo.substring(colon + 1)};
                }
                return new String[] {userInfo, ""};
            }
        } catch (Exception e) {
            log.debug("Could not parse userinfo from request URL", e);
        }

        return null;
    }

    private String buildAuthenticatedUrl(String[] userPass, String repoPath) {
        String host = provider.getUri().getHost();
        if (userPass != null) {
            return "https://" + userPass[0] + ":" + userPass[1] + "@" + host + "/" + repoPath + ".git";
        }
        return "https://" + host + "/" + repoPath + ".git";
    }
}
