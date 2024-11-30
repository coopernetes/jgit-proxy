package com.github.coopernetes.jgitproxy.github;

import com.github.coopernetes.jgitproxy.git.GitClientUtils;
import com.github.coopernetes.jgitproxy.github.rest.GithubClient;
import com.github.coopernetes.jgitproxy.servlet.UrlAwareGitFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.http.server.GitSmartHttpTools;

/**
 * Filter that checks if the request is authenticated with a valid GitHub token.
 * If the request is not authenticated, it sends an error response to the client.
 */
@Slf4j
public class GithubAuthenticationFilter extends UrlAwareGitFilter implements GithubFilter {

    private final GithubClient githubClient;

    public GithubAuthenticationFilter(GithubClient githubClient) {
        super(PROVIDER);
        this.githubClient = githubClient;
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        // ignoring /info/refs?service=... and clone/fetches requests
        if (GitSmartHttpTools.isInfoRefs(request) || GitSmartHttpTools.isUploadPack(request)) {
            chain.doFilter(request, response);
            return;
        }
        String authValue = request.getHeader("Authorization");
        log.debug("Authorization: {}", authValue); // TODO: remove, obviously insecure
        if (authValue == null || !authValue.startsWith("Bearer ") || !isValidToken(authValue)) {
            if (GitSmartHttpTools.isUploadPack(request)) {
                sendGitError(
                        request,
                        response,
                        GitClientUtils.clientMessage(
                                "\u26A0\uFE0F  Unauthorized! \u26A0\uFE0F",
                                "You must provide a valid personal access token."));
            } else {
                sendGitError(
                        request,
                        response,
                        GitClientUtils.clientMessage(
                                "\u26A0\uFE0F  Unauthorized! \u26A0\uFE0F",
                                "You must provide a valid personal access token.",
                                GitClientUtils.AnsiColor.YELLOW));
            }
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isValidToken(String authValue) {
        return githubClient.getUserInfo(authValue).isPresent();
    }
}
