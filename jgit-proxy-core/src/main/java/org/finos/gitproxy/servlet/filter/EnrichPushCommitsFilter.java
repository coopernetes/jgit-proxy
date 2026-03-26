package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyProviderServlet.GIT_REQUEST_ATTRIBUTE;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PackParser;
import org.finos.gitproxy.git.Commit;
import org.finos.gitproxy.git.CommitInspectionService;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.git.LocalRepositoryCache;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.servlet.RequestBodyWrapper;

/**
 * Filter that enriches push requests with full commit information. Replicates git-proxy's {@code writePack} approach:
 *
 * <ol>
 *   <li>Clone/fetch the upstream repo into a local cache
 *   <li>Unpack the inflight push's pack data into the local clone (the objects don't exist upstream yet)
 *   <li>Use {@link CommitInspectionService} to walk the commit range with full details
 * </ol>
 *
 * <p>This gives downstream filters (author email, commit message, etc.) access to full commit metadata — author,
 * message, signature — rather than just the basic SHA/ref from the packet line header.
 */
@Slf4j
public class EnrichPushCommitsFilter extends AbstractProviderAwareGitProxyFilter {

    private static final int ORDER = Integer.MIN_VALUE + 2; // Run after ParseGitRequestFilter
    private final LocalRepositoryCache repositoryCache;

    public EnrichPushCommitsFilter(GitProxyProvider provider, LocalRepositoryCache repositoryCache) {
        super(ORDER, Set.of(HttpOperation.PUSH), provider);
        this.repositoryCache = repositoryCache;
    }

    public EnrichPushCommitsFilter(GitProxyProvider provider, LocalRepositoryCache repositoryCache, String pathPrefix) {
        super(ORDER, Set.of(HttpOperation.PUSH), provider, pathPrefix);
        this.repositoryCache = repositoryCache;
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var requestDetails = (GitRequestDetails) request.getAttribute(GIT_REQUEST_ATTRIBUTE);
        if (requestDetails == null) {
            log.warn("GitRequestDetails not found in request attributes");
            return;
        }

        // Use the packet line SHAs (the ref update range), not the pack data's commit parent
        String fromCommit = requestDetails.getCommitFrom();
        String toCommit = requestDetails.getCommitTo();
        if (toCommit == null || toCommit.isEmpty()) {
            log.debug("No commit range available from packet line");
            return;
        }

        try {
            String remoteUrl = constructRemoteUrl(requestDetails);
            log.info("Enriching push commits from repository: {}", remoteUrl);

            // Step 1: Get or clone the upstream repo
            Repository repository = repositoryCache.getOrClone(remoteUrl);

            // Step 2: Unpack the inflight push's pack data into the local clone.
            // The pushed objects don't exist upstream yet — this is the equivalent of
            // git-proxy's writePack processor that pipes the request body into git receive-pack.
            unpackPushData(request, repository);

            log.debug("Extracting commits from {} to {}", fromCommit, toCommit);

            List<Commit> commits = CommitInspectionService.getCommitRange(repository, fromCommit, toCommit);

            if (commits.isEmpty()) {
                log.warn("No commits found in range {}..{}", fromCommit, toCommit);
                addFallbackCommit(requestDetails);
            } else {
                log.info("Extracted {} commits from repository", commits.size());
                requestDetails.getPushedCommits().addAll(commits);
            }

        } catch (Exception e) {
            log.error("Failed to enrich push commits", e);
            addFallbackCommit(requestDetails);
        }
    }

    /**
     * Unpack the push's pack data from the cached request body into the local repository. The request body contains git
     * protocol packet lines followed by pack data (starting with the "PACK" signature). We extract the pack portion and
     * feed it to JGit's {@link PackParser} to insert the objects into the local object store.
     *
     * <p>This is the JGit equivalent of git-proxy's {@code writePack} processor which runs {@code git receive-pack}
     * with the request body as stdin.
     */
    private void unpackPushData(HttpServletRequest request, Repository repository) throws IOException {
        byte[] body = getRequestBody(request);
        if (body == null || body.length == 0) {
            log.debug("No request body to unpack");
            return;
        }

        // Find the PACK signature in the request body
        int packOffset = findPackSignature(body);
        if (packOffset < 0) {
            log.debug("No PACK signature found in request body");
            return;
        }

        log.debug("Found PACK data at offset {} ({} bytes)", packOffset, body.length - packOffset);

        // Unpack the objects into the local repo's object store
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            PackParser parser =
                    inserter.newPackParser(new ByteArrayInputStream(body, packOffset, body.length - packOffset));
            parser.setAllowThin(true); // Allow thin packs (deltas against objects already in the repo)
            parser.parse(NullProgressMonitor.INSTANCE);
            inserter.flush();
            log.debug("Successfully unpacked push objects into local repository");
        }
    }

    /** Extract the cached request body from the {@link RequestBodyWrapper}. */
    private byte[] getRequestBody(HttpServletRequest request) {
        if (request instanceof RequestBodyWrapper) {
            return ((RequestBodyWrapper) request).getBody();
        }
        // Try unwrapping
        if (request instanceof jakarta.servlet.http.HttpServletRequestWrapper wrapper) {
            var wrapped = wrapper.getRequest();
            if (wrapped instanceof RequestBodyWrapper bodyWrapper) {
                return bodyWrapper.getBody();
            }
        }
        log.warn("Request is not a RequestBodyWrapper — cannot extract cached body");
        return null;
    }

    /** Scan for the PACK signature (bytes 'P','A','C','K') in the request body. */
    private int findPackSignature(byte[] data) {
        for (int i = 0; i < data.length - 4; i++) {
            if (data[i] == 'P' && data[i + 1] == 'A' && data[i + 2] == 'C' && data[i + 3] == 'K') {
                return i;
            }
        }
        return -1;
    }

    private void addFallbackCommit(GitRequestDetails requestDetails) {
        if (requestDetails.getCommit() != null) {
            requestDetails.getPushedCommits().add(requestDetails.getCommit());
        }
    }

    private String constructRemoteUrl(GitRequestDetails requestDetails) {
        String providerHost = provider.getUri().getHost();
        String slug = requestDetails.getRepository().getSlug();
        return String.format("https://%s/%s.git", providerHost, slug);
    }
}
