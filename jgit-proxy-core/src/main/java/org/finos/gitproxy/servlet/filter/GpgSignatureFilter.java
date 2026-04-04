package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.config.GpgConfig;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.validation.GpgSignatureCheck;
import org.finos.gitproxy.validation.Violation;

/**
 * Proxy-mode adapter for {@link GpgSignatureCheck}. Reads commits from {@link GitRequestDetails} and translates
 * violations into filter-chain rejections.
 *
 * <p>This filter runs at order 320, which is in the content filters range (200-399).
 */
@Slf4j
public class GpgSignatureFilter extends AbstractGitProxyFilter {

    private static final int ORDER = 320;
    private final GpgSignatureCheck check;

    public GpgSignatureFilter(GpgConfig config) {
        super(ORDER, Set.of(HttpOperation.PUSH));
        this.check = new GpgSignatureCheck(config);
    }

    @Override
    public String getStepName() {
        return "checkSignatures";
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
            log.debug("GPG signature check passed");
            return;
        }

        log.warn("GPG signature check failed: {} violation(s)", violations.size());
        for (Violation v : violations) {
            recordIssue(request, v.reason(), v.formattedDetail());
        }
    }
}
