package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.git.GitClient.AnsiColor.*;
import static org.finos.gitproxy.git.GitClient.SymbolCodes.*;
import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.finos.gitproxy.git.Commit;
import org.finos.gitproxy.git.GitClient;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.git.LocalRepositoryCache;
import org.finos.gitproxy.provider.GitProxyProvider;

/**
 * Filter that detects "hidden" commits — commits present in the push pack (and thus new to the upstream) that fall
 * outside the explicitly introduced commit range. This catches the case where a branch was built on top of unapproved
 * commits that weren't pushed to the upstream yet, smuggling them in as pack filler.
 *
 * <p>Algorithm:
 *
 * <ol>
 *   <li><b>introduced</b> — {@code requestDetails.getPushedCommits()} as populated by {@link EnrichPushCommitsFilter}.
 *   <li><b>allNew</b> — commits reachable from {@code commitTo} that are not reachable from any ref in the upstream
 *       clone (i.e., genuinely new to the upstream); computed via {@link RevWalk} on the cached repository.
 *   <li><b>hidden</b> = {@code allNew} ∖ {@code introduced}.
 * </ol>
 *
 * <p>This filter short-circuits immediately via {@link #rejectAndSendError} without recording to
 * {@link ValidationSummaryFilter}. Requires {@link EnrichPushCommitsFilter} to have run first (for both
 * {@code pushedCommits} and the unpacked local repository).
 *
 * <p>Runs at order 2060, before all other content validation filters.
 */
@Slf4j
public class CheckHiddenCommitsFilter extends AbstractProviderAwareGitProxyFilter {

    private static final int ORDER = 2060;
    private static final String PROXY_PATH_PREFIX = "/proxy";

    private final LocalRepositoryCache repositoryCache;

    public CheckHiddenCommitsFilter(GitProxyProvider provider, LocalRepositoryCache repositoryCache) {
        super(ORDER, Set.of(HttpOperation.PUSH), provider, PROXY_PATH_PREFIX);
        this.repositoryCache = repositoryCache;
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var requestDetails = (GitRequestDetails) request.getAttribute(GIT_REQUEST_ATTR);
        if (requestDetails == null) {
            log.warn("GitRequestDetails not found in request attributes");
            return;
        }

        String toCommit = requestDetails.getCommitTo();
        if (toCommit == null || toCommit.isEmpty() || toCommit.matches("^0+$")) {
            log.debug("No commitTo in request details, skipping hidden commits check");
            return;
        }

        Set<String> introduced = requestDetails.getPushedCommits() == null
                ? Set.of()
                : requestDetails.getPushedCommits().stream().map(Commit::getSha).collect(Collectors.toSet());

        try {
            String remoteUrl = constructRemoteUrl(requestDetails);
            Repository repository = repositoryCache.getOrClone(remoteUrl);

            Set<String> allNew = collectAllNewCommits(repository, toCommit);

            Set<String> hidden = new HashSet<>(allNew);
            hidden.removeAll(introduced);

            if (hidden.isEmpty()) {
                log.debug("checkHiddenCommits: all {} new commit(s) are within the introduced range", allNew.size());
                return;
            }

            log.warn("checkHiddenCommits: {} hidden commit(s) detected: {}", hidden.size(), hidden);

            String title = NO_ENTRY.emoji() + "  Push Blocked — Hidden Commits Detected";
            String message = "Unreferenced commits in pack (" + hidden.size() + "): "
                    + String.join(", ", hidden) + ".\n\n"
                    + "This usually happens when a branch was made from a commit that hasn't been approved"
                    + " and pushed to the remote.\n"
                    + "Please get approval on the commits, push them and try again.";

            rejectAndSendError(
                    request, response, "Hidden commits detected", GitClient.format(title, message, RED, null));

        } catch (Exception e) {
            log.error("Failed to check hidden commits", e);
        }
    }

    /**
     * Collect all commits reachable from {@code toCommit} that are not reachable from any existing ref in the upstream
     * clone. These correspond to the commits that were new to the upstream in this pack.
     */
    private Set<String> collectAllNewCommits(Repository repo, String toCommit) throws IOException {
        Set<String> result = new HashSet<>();

        try (RevWalk walk = new RevWalk(repo)) {
            ObjectId tip = repo.resolve(toCommit);
            if (tip == null) {
                log.warn("Could not resolve commitTo {} in cached repository", toCommit);
                return result;
            }
            walk.markStart(walk.parseCommit(tip));

            for (Ref ref : repo.getRefDatabase().getRefsByPrefix("refs/")) {
                ObjectId id = ref.getObjectId();
                if (id == null) continue;
                try {
                    walk.markUninteresting(walk.parseCommit(id));
                } catch (Exception e) {
                    // Not a commit (annotated tag pointing to a blob/tree, etc.) — skip
                }
            }

            for (RevCommit commit : walk) {
                result.add(commit.getName());
            }
        }

        return result;
    }

    private String constructRemoteUrl(GitRequestDetails requestDetails) {
        String providerHost = provider.getUri().getHost();
        String slug = requestDetails.getRepository().getSlug();
        return String.format("https://%s/%s.git", providerHost, slug);
    }
}
