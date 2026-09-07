package com.rbc.fogwall.servlet.filter;

import com.rbc.fogwall.git.RepoPath;
import com.rbc.fogwall.git.UpstreamAuthProbe;
import com.rbc.fogwall.provider.FogwallProvider;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.http.server.GitSmartHttpTools;

/**
 * Servlet filter that challenges unauthenticated requests with HTTP 401 and {@code WWW-Authenticate: Basic}. Git
 * clients only send credentials after receiving a 401 challenge - without this, credentials embedded in the remote URL
 * (e.g. {@code http://user:token@proxy/...}) are never transmitted.
 *
 * <p><b>Push is always challenged.</b> Server mode forwards the push upstream using the developer's own credentials, so
 * there is no such thing as an anonymous push through fogwall.
 *
 * <p><b>Fetch is challenged only when the upstream repository actually needs it.</b> The mirror is cloned from upstream
 * on every open, so a fetch of a private repository must be able to carry credentials — but challenging every fetch
 * makes public repositories unclonable by anyone who has no credential to offer, and a client that answers the
 * challenge with an unrelated or expired token is rejected by providers such as GitHub even on a repository they would
 * have served anonymously. {@link UpstreamAuthProbe} asks upstream instead of guessing, and its answer is cached.
 *
 * <p>Without a probe configured, every fetch is challenged. That is the conservative reading and is what fogwall did
 * before the probe existed.
 *
 * <p>Matches both the {@code info/refs} advertisement and the actual {@code POST /git-upload-pack} or {@code POST
 * /git-receive-pack} data exchange.
 */
@Slf4j
public class BasicAuthChallengeFilter implements Filter {

    private final FogwallProvider provider;
    private final UpstreamAuthProbe probe;

    /** Challenges every git request, authenticated or not. */
    public BasicAuthChallengeFilter() {
        this(null, null);
    }

    /**
     * Challenges pushes always, and fetches only when {@code probe} reports that upstream refuses anonymous reads.
     *
     * @param provider used to build the upstream URL to probe; a null provider disables probing
     * @param probe the readability probe; a null probe disables probing
     */
    public BasicAuthChallengeFilter(FogwallProvider provider, UpstreamAuthProbe probe) {
        this.provider = provider;
        this.probe = probe;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var httpReq = (HttpServletRequest) request;
        var httpResp = (HttpServletResponse) response;

        if (isGitSmartHttpRequest(httpReq) && !hasCredentials(httpReq) && needsCredentials(httpReq)) {
            httpResp.setHeader("WWW-Authenticate", "Basic realm=\"fogwall\"");
            httpResp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        chain.doFilter(request, response);
    }

    private static boolean hasCredentials(HttpServletRequest req) {
        String auth = req.getHeader("Authorization");
        return auth != null && !auth.isBlank();
    }

    /** Whether this unauthenticated request should be challenged rather than allowed through anonymously. */
    private boolean needsCredentials(HttpServletRequest req) {
        if (!isFetch(req)) return true; // push, or anything unrecognised
        if (provider == null || probe == null) return true;

        String upstreamUrl = upstreamUrl(req);
        if (upstreamUrl == null) return true;

        return probe.requiresAuthentication(upstreamUrl);
    }

    /**
     * Whether this is a read. {@code info/refs} carries the intended service as a query parameter; the data-exchange
     * POSTs identify themselves by path and content type.
     */
    private static boolean isFetch(HttpServletRequest req) {
        if (GitSmartHttpTools.isInfoRefs(req)) {
            return "git-upload-pack".equals(req.getParameter("service"));
        }
        return GitSmartHttpTools.isUploadPack(req);
    }

    /**
     * Builds the upstream repository URL from the request path, or null if the path does not name a repository. Mirrors
     * {@code ServerRepositoryResolver}: the same slug validation applies, because an invalid segment must not reach an
     * outbound URL here either.
     */
    private String upstreamUrl(HttpServletRequest req) {
        Optional<RepoPath> repoPath = RepoPath.parse(req.getPathInfo());
        if (repoPath.isEmpty()) {
            log.debug("Not probing upstream for invalid repository path: {}", req.getPathInfo());
            return null;
        }
        return provider.getUri() + repoPath.get().slug() + ".git";
    }

    private boolean isGitSmartHttpRequest(HttpServletRequest req) {
        return GitSmartHttpTools.isReceivePack(req)
                || GitSmartHttpTools.isUploadPack(req)
                || GitSmartHttpTools.isInfoRefs(req);
    }
}
