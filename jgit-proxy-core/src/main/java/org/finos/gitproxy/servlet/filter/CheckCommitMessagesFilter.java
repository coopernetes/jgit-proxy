package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.validation.CommitMessageCheck;
import org.finos.gitproxy.validation.Violation;

/**
 * Proxy-mode adapter for {@link CommitMessageCheck}. Reads commits from {@link GitRequestDetails} and translates
 * violations into filter-chain rejections.
 *
 * <p>This filter runs at order 2200, which is in the built-in content filters range (2000-4999).
 */
@Slf4j
public class CheckCommitMessagesFilter extends AbstractGitProxyFilter {

    private static final int ORDER = 2200;
    private final CommitMessageCheck check;

    public CheckCommitMessagesFilter(CommitConfig commitConfig) {
        super(ORDER, Set.of(HttpOperation.PUSH));
        this.check = new CommitMessageCheck(commitConfig != null ? commitConfig : CommitConfig.defaultConfig());
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var requestDetails = (GitRequestDetails) request.getAttribute(GIT_REQUEST_ATTR);
        if (requestDetails == null) {
            log.warn("GitRequestDetails not found in request attributes");
            return;
        }

        var commits = requestDetails.getPushedCommits();
        if (commits == null || commits.isEmpty()) {
            log.debug("No commits to validate");
            return;
        }

        List<Violation> violations = check.check(commits);
        if (violations.isEmpty()) {
            log.debug("All commit messages passed");
            return;
        }

        log.warn("Commit message check failed: {} violation(s)", violations.size());
        for (Violation v : violations) {
            recordIssue(request, v.reason(), v.formattedDetail());
        }
    }
}
