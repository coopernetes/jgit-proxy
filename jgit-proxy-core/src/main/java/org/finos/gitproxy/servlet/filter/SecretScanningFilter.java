package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.git.GitClientUtils.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClientUtils.sym;
import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.GitleaksRunner;
import org.finos.gitproxy.git.HttpOperation;

/**
 * Proxy-mode secret scanning filter. Runs {@code gitleaks git} against the local repository populated by
 * {@link EnrichPushCommitsFilter}, using the commit range from {@link GitRequestDetails}.
 *
 * <p>Path-based allowlists in gitleaks rules (e.g. suppressing {@code package-lock.json} integrity hashes) are applied
 * correctly because gitleaks has full file-path context when operating in git mode.
 *
 * <p>Runs at order 340, after {@link ScanDiffFilter} (order 300), within the content filters range (200-399).
 */
@Slf4j
public class SecretScanningFilter extends AbstractGitProxyFilter {

    private static final int ORDER = 340;

    private final CommitConfig.SecretScanningConfig config;
    private final GitleaksRunner runner;

    public SecretScanningFilter(CommitConfig.SecretScanningConfig config, GitleaksRunner runner) {
        super(ORDER, Set.of(HttpOperation.PUSH));
        this.config = config;
        this.runner = runner;
    }

    public SecretScanningFilter(CommitConfig.SecretScanningConfig config) {
        this(config, new GitleaksRunner());
    }

    @Override
    public String getStepName() {
        return "scanSecrets";
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var requestDetails = (GitRequestDetails) request.getAttribute(GIT_REQUEST_ATTR);
        if (requestDetails == null) {
            log.warn("GitRequestDetails not found in request attributes");
            return;
        }

        if (!config.isEnabled()) {
            log.debug("Secret scanning disabled - skipping");
            return;
        }

        var repo = requestDetails.getLocalRepository();
        if (repo == null) {
            log.warn(
                    "localRepository not set on request - EnrichPushCommitsFilter may not have run; skipping secret scan (fail-open)");
            return;
        }

        String commitFrom = requestDetails.getCommitFrom();
        String commitTo = requestDetails.getCommitTo();
        if (commitTo == null || commitTo.isBlank()) {
            log.debug("No commitTo in request details - skipping secret scan");
            return;
        }

        Optional<List<GitleaksRunner.Finding>> result =
                runner.scanGit(repo.getDirectory().toPath(), commitFrom, commitTo, config);

        if (result.isEmpty()) {
            // Fail-open: scanner unavailable - GitleaksRunner already logged the detail
            return;
        }

        List<GitleaksRunner.Finding> findings = result.get();
        if (findings.isEmpty()) {
            log.debug("Secret scan passed - no findings");
            return;
        }

        log.warn("Secret scan found {} finding(s)", findings.size());
        for (GitleaksRunner.Finding f : findings) {
            String msg = f.toMessage();
            recordIssue(request, msg, sym(CROSS_MARK) + "  " + msg);
        }
    }
}
