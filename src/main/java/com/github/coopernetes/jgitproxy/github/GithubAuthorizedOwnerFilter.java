package com.github.coopernetes.jgitproxy.github;

import com.github.coopernetes.jgitproxy.servlet.UrlAwareGitFilter;
import java.util.List;

/**
 * Filter that only permits requests from a configured list of authorized owners. It's
 * expected that filters are subclassed to implement the specific behavior for a git
 * fetch or git push since they are different operations.
 */
public abstract class GithubAuthorizedOwnerFilter extends UrlAwareGitFilter implements GithubFilter {
    private final List<String> authorizedOwners;

    public GithubAuthorizedOwnerFilter(List<String> authorizedOwners) {
        super(PROVIDER);
        this.authorizedOwners = authorizedOwners;
    }

    protected String getOwner(String uri) {
        return uri.split("/")[2];
    }

    protected boolean isAuthorized(String owner) {
        return authorizedOwners.contains(owner);
    }
}
