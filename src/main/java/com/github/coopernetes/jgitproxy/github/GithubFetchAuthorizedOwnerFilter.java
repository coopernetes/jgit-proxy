package com.github.coopernetes.jgitproxy.github;

import com.github.coopernetes.jgitproxy.git.GitClientUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Filter that runs on git fetches and validates if the application is configured
 * permit fetches (authorize) from the owner of target (upstream) the repository.
 */
@Slf4j
public class GithubFetchAuthorizedOwnerFilter extends GithubAuthorizedOwnerFilter {
    public GithubFetchAuthorizedOwnerFilter(List<String> owners) {
        super(owners);
    }

    @Override
    protected void doHttpFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var owner = getOwner(request.getRequestURI());
        log.debug("Owner: {}", owner);
        if (!isAuthorized(owner)) {
            sendGitError(
                    request,
                    response,
                    GitClientUtils.clientMessage(
                            "⛔ Unauthorized! ⛔", "You are not authorized to fetch from this repository."));
            return;
        }
        chain.doFilter(request, response);
    }
}
