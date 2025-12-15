package org.finos.gitproxy.git;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;

/**
 * Repository resolver that uses a local cache of cloned repositories. This resolver integrates with the
 * LocalRepositoryCache to provide access to locally cached repository clones for git operations.
 */
@Slf4j
@RequiredArgsConstructor
public class TemporaryRepositoryResolver implements RepositoryResolver<HttpServletRequest> {

    private final LocalRepositoryCache cache;

    @Override
    public Repository open(HttpServletRequest req, String name)
            throws RepositoryNotFoundException, ServiceNotAuthorizedException, ServiceNotEnabledException,
                    ServiceMayNotContinueException {

        // Extract the repository URL from the request
        String remoteUrl = extractRemoteUrl(req, name);

        try {
            // Get or clone the repository
            Repository repository = cache.getOrClone(remoteUrl);
            log.debug("Opened repository from cache: {}", name);
            return repository;
        } catch (Exception e) {
            log.error("Failed to open repository: {}", name, e);
            throw new RepositoryNotFoundException(name, e);
        }
    }

    /**
     * Extract the remote repository URL from the request.
     *
     * @param req The servlet request
     * @param name The repository name
     * @return The remote repository URL
     */
    private String extractRemoteUrl(HttpServletRequest req, String name) {
        // Extract authentication from Authorization header
        String authHeader = req.getHeader("Authorization");
        String authCredentials = "";

        if (authHeader != null && authHeader.startsWith("Basic ")) {
            try {
                // Extract credentials from Basic auth
                String base64Credentials =
                        authHeader.substring("Basic ".length()).trim();
                String credentials = new String(java.util.Base64.getDecoder().decode(base64Credentials));
                // credentials format is "username:password"
                authCredentials = credentials + "@";
            } catch (IllegalArgumentException e) {
                log.warn("Invalid Base64 encoding in Authorization header", e);
                // Continue without auth credentials
            }
        }

        // This is a simplified implementation
        // In a real implementation, you would extract the provider and construct the full URL
        // For now, we'll use the request info
        String pathInfo = req.getPathInfo();
        if (pathInfo != null && pathInfo.length() > 1) {
            // Remove leading slash and any git-receive-pack or git-upload-pack suffixes
            String cleanPath = pathInfo.substring(1).replaceAll("/(git-receive-pack|git-upload-pack|info/refs).*$", "");

            // Construct URL with authentication if present
            // Format: https://username:password@host/path.git
            if (!authCredentials.isEmpty()) {
                return "https://" + authCredentials + cleanPath + ".git";
            } else {
                return "https://" + cleanPath + ".git";
            }
        }

        return name;
    }
}
