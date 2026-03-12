package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyProviderServlet.GIT_REQUEST_ATTRIBUTE;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Repository;
import org.finos.gitproxy.git.Commit;
import org.finos.gitproxy.git.CommitInspectionService;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.git.LocalRepositoryCache;
import org.finos.gitproxy.provider.GitProxyProvider;

/**
 * Filter that enriches push requests with full commit information by using a local repository cache. This filter uses
 * JGit to clone/fetch the remote repository and extract all commits in the push range.
 */
@Slf4j
public class EnrichPushCommitsFilter extends AbstractProviderAwareGitProxyFilter {

    private static final int ORDER = Integer.MIN_VALUE + 2; // Run after ParseGitRequestFilter
    private final LocalRepositoryCache repositoryCache;

    public EnrichPushCommitsFilter(GitProxyProvider provider, LocalRepositoryCache repositoryCache) {
        super(ORDER, Set.of(HttpOperation.PUSH), provider);
        this.repositoryCache = repositoryCache;
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var requestDetails = (GitRequestDetails) request.getAttribute(GIT_REQUEST_ATTRIBUTE);
        if (requestDetails == null) {
            log.warn("GitRequestDetails not found in request attributes");
            return;
        }

        // Get the single commit from the basic parse
        Commit basicCommit = requestDetails.getCommit();
        if (basicCommit == null) {
            log.debug("No commit information available from basic parse");
            return;
        }

        try {
            // Construct the remote URL
            String remoteUrl = constructRemoteUrl(requestDetails);
            log.info("Enriching push commits from repository: {}", remoteUrl);

            // Get or clone the repository
            Repository repository = repositoryCache.getOrClone(remoteUrl);

            // Get the commit range
            String fromCommit = basicCommit.getParent();
            String toCommit = basicCommit.getSha();

            log.debug("Extracting commits from {} to {}", fromCommit, toCommit);

            // Extract all commits in the range
            List<Commit> commits = CommitInspectionService.getCommitRange(repository, fromCommit, toCommit);

            if (commits.isEmpty()) {
                log.warn("No commits found in range {}..{}", fromCommit, toCommit);
                // Add the basic commit at least
                requestDetails.getPushedCommits().add(basicCommit);
            } else {
                log.info("Extracted {} commits from repository", commits.size());
                requestDetails.getPushedCommits().addAll(commits);
            }

        } catch (Exception e) {
            log.error("Failed to enrich push commits", e);
            // Fall back to basic commit
            requestDetails.getPushedCommits().add(basicCommit);
        }
    }

    /**
     * Construct the remote repository URL from request details.
     *
     * @param requestDetails The request details
     * @return The remote repository URL
     */
    private String constructRemoteUrl(GitRequestDetails requestDetails) {
        String providerHost = provider.getUri().getHost();
        String slug = requestDetails.getRepository().getSlug();
        return String.format("https://%s/%s.git", providerHost, slug);
    }
}
