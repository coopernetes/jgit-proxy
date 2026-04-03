package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.git.GitClientUtils.AnsiColor.*;
import static org.finos.gitproxy.git.GitClientUtils.SymbolCodes.*;
import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.db.model.PushStep;
import org.finos.gitproxy.db.model.StepStatus;
import org.finos.gitproxy.git.CommitInspectionService;
import org.finos.gitproxy.git.DiffGenerationHook;
import org.finos.gitproxy.git.GitClientUtils;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.git.LocalRepositoryCache;
import org.finos.gitproxy.provider.GitProxyProvider;

/**
 * Filter that scans the diff content of incoming pushes for blocked literals and patterns. Runs in the transparent
 * proxy pipeline after {@link EnrichPushCommitsFilter} (which has already cloned the repo and unpacked push objects),
 * so the local repository is available as a cache hit.
 *
 * <p>Only added lines (prefixed with {@code +} in the unified diff, excluding the {@code +++} header) are scanned.
 * Deletions and context lines are ignored.
 *
 * <p>This filter runs at order 2300, in the built-in content filters range (2000-4999).
 */
@Slf4j
public class ScanDiffFilter extends AbstractProviderAwareGitProxyFilter {

    private static final int ORDER = 2300;

    private final CommitConfig commitConfig;
    private final LocalRepositoryCache repositoryCache;

    public ScanDiffFilter(GitProxyProvider provider, CommitConfig commitConfig, LocalRepositoryCache repositoryCache) {
        super(ORDER, Set.of(HttpOperation.PUSH), provider, PROXY_PATH_PREFIX);
        this.commitConfig = commitConfig != null ? commitConfig : CommitConfig.defaultConfig();
        this.repositoryCache = repositoryCache;
    }

    private static final String PROXY_PATH_PREFIX = "/proxy";

    @Override
    public String getStepName() {
        return "scanDiff";
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var requestDetails = (GitRequestDetails) request.getAttribute(GIT_REQUEST_ATTR);
        if (requestDetails == null) {
            log.warn("GitRequestDetails not found in request attributes");
            return;
        }

        String fromCommit = requestDetails.getCommitFrom();
        String toCommit = requestDetails.getCommitTo();
        if (toCommit == null || toCommit.isEmpty()) {
            log.debug("No commit range in request details, skipping diff generation");
            return;
        }

        try {
            String remoteUrl = constructRemoteUrl(requestDetails);
            org.eclipse.jgit.lib.Repository repository = repositoryCache.getOrClone(remoteUrl);

            String diff = CommitInspectionService.getFormattedDiff(repository, fromCommit, toCommit);

            // Always record the diff so the dashboard can display it
            PushStep diffStep = PushStep.builder()
                    .pushId(requestDetails.getId().toString())
                    .stepName(DiffGenerationHook.STEP_NAME_PUSH_DIFF)
                    .stepOrder(ORDER - 50)
                    .status(StepStatus.PASS)
                    .content(diff)
                    .build();
            requestDetails.getSteps().add(diffStep);

            CommitConfig.BlockConfig block = commitConfig.getDiff().getBlock();
            if (block.getLiterals().isEmpty() && block.getPatterns().isEmpty()) {
                log.debug("No diff block rules configured, skipping diff scan");
                recordStep(request, StepStatus.PASS, null, null);
                return;
            }

            if (diff.isEmpty()) {
                log.debug("Empty diff, nothing to scan");
                recordStep(request, StepStatus.PASS, null, null);
                return;
            }

            List<String> violations = scanDiff(diff, block);

            if (!violations.isEmpty()) {
                log.warn("Diff scan found {} violation(s)", violations.size());
                String title = NO_ENTRY.emoji() + "  Push Blocked — Diff Contains Blocked Content";
                String violationList = violations.stream()
                        .map(v -> CROSS_MARK.emoji() + "  " + v)
                        .collect(Collectors.joining("\n"));
                String message = "Diff content contains blocked patterns:\n\n" + violationList;
                recordIssue(request, "Diff contains blocked content", GitClientUtils.format(title, message, RED, null));
            } else {
                log.debug("Diff scan passed for {}..{}", fromCommit, toCommit);
                recordStep(request, StepStatus.PASS, null, null);
            }

        } catch (Exception e) {
            log.error("Failed to generate/scan diff for push {}..{}", fromCommit, toCommit, e);
        }
    }

    /**
     * Scans the unified diff for blocked content. Only added lines are checked. Returns a deduplicated list of
     * violation descriptions.
     */
    List<String> scanDiff(String diff, CommitConfig.BlockConfig block) {
        Set<String> violations = new LinkedHashSet<>();
        String currentFile = null;

        for (String line : diff.lines().toList()) {
            if (line.startsWith("diff --git ")) {
                currentFile = extractFileName(line);
            }

            // Only scan added lines; skip the +++ file header
            if (!line.startsWith("+") || line.startsWith("+++")) {
                continue;
            }
            String content = line.substring(1);

            for (String literal : block.getLiterals()) {
                if (content.toLowerCase().contains(literal.toLowerCase())) {
                    String location = currentFile != null ? " in " + currentFile : "";
                    violations.add("blocked term: \"" + literal + "\"" + location);
                }
            }

            for (Pattern pattern : block.getPatterns()) {
                if (pattern.matcher(content).find()) {
                    String location = currentFile != null ? " in " + currentFile : "";
                    violations.add("blocked pattern: " + pattern.pattern() + location);
                }
            }
        }

        return new ArrayList<>(violations);
    }

    /** Extracts the {@code b/} path from a {@code diff --git a/... b/...} header line. */
    private static String extractFileName(String diffHeader) {
        String[] parts = diffHeader.split(" ");
        if (parts.length >= 4) {
            String bPath = parts[3];
            return bPath.startsWith("b/") ? bPath.substring(2) : bPath;
        }
        return diffHeader;
    }

    private String constructRemoteUrl(GitRequestDetails requestDetails) {
        String providerHost = provider.getUri().getHost();
        String slug = requestDetails.getRepository().getSlug();
        return String.format("https://%s/%s.git", providerHost, slug);
    }
}
