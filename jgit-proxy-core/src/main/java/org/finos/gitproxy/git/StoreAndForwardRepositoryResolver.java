package org.finos.gitproxy.git;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.finos.gitproxy.provider.GitProxyProvider;

/**
 * Repository resolver for store-and-forward mode. Syncs a local bare repo from the upstream provider on each open,
 * ensuring the local mirror is fresh for both fetch and push operations.
 *
 * <p>Supports both public and private repositories. Client credentials are extracted from the request and used
 * transiently for the upstream clone/fetch — they are never written to disk. The same credentials are stored as a
 * request attribute ({@link #CREDENTIALS_ATTRIBUTE}) so that {@link StoreAndForwardReceivePackFactory} can pass them to
 * {@link ForwardingPostReceiveHook} for the upstream push.
 */
@Slf4j
@RequiredArgsConstructor
public class StoreAndForwardRepositoryResolver implements RepositoryResolver<HttpServletRequest> {

    public static final String CREDENTIALS_ATTRIBUTE = "org.finos.gitproxy.credentials";

    private final LocalRepositoryCache cache;
    private final GitProxyProvider provider;

    @Override
    public Repository open(HttpServletRequest req, String name)
            throws RepositoryNotFoundException, ServiceNotAuthorizedException, ServiceNotEnabledException {

        // JGit passes name as the path after the servlet mapping, e.g. "owner/repo.git"
        String cleanName = name.replaceAll("\\.git$", "");

        // Construct the clean upstream URL (no credentials, ever)
        String cleanUpstreamUrl = provider.getUri() + "/" + cleanName + ".git";

        // Extract client credentials — used for both the upstream clone/fetch AND the upstream push.
        // Credentials are held in memory only and never written to disk.
        String[] userPass = extractCredentials(req);
        CredentialsProvider creds = null;
        if (userPass != null) {
            creds = new UsernamePasswordCredentialsProvider(userPass[0], userPass[1]);
            req.setAttribute(CREDENTIALS_ATTRIBUTE, creds);
            req.setAttribute("org.finos.gitproxy.pushUser", userPass[0]);
        }

        log.info("Opening store-and-forward repository: {} -> {}", name, cleanUpstreamUrl);

        try {
            Repository repo = cache.getOrClone(cleanUpstreamUrl, creds);

            // Store the upstream URL so PostReceiveHook can find it
            repo.getConfig().setString("gitproxy", null, "upstreamUrl", cleanUpstreamUrl);
            repo.getConfig().save();

            return repo;
        } catch (Exception e) {
            log.error("Failed to open repository: {} from upstream {}", name, cleanUpstreamUrl, e);
            throw new RepositoryNotFoundException(name, e);
        }
    }

    /**
     * Extract credentials from either the Authorization header or the URL userinfo. These are used only for the
     * upstream push via {@link ForwardingPostReceiveHook}, never for cloning or caching.
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

        // Fall back to URL userinfo - git embeds user:pass in the request URL
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
}
