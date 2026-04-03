package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.git.GitClient.AnsiColor.*;
import static org.finos.gitproxy.git.GitClient.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClient.sym;
import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.db.model.PushStep;
import org.finos.gitproxy.git.DiffGenerationHook;
import org.finos.gitproxy.git.GitClient;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.GitleaksRunner;
import org.finos.gitproxy.git.HttpOperation;

/**
 * Filter that scans the diff of incoming pushes for secrets using gitleaks.
 *
 * <p>The diff is read from the {@link PushStep} already recorded by {@link ScanDiffFilter} (step name {@code "diff"}).
 * If no such step exists the filter skips scanning. This ensures gitleaks sees exactly the same diff the dashboard
 * displays — no redundant re-generation.
 *
 * <p>If the gitleaks binary is unavailable (bundled binary not present for the current platform, or an error executing
 * it) the filter logs a warning and allows the push through (fail-open). Pushes are never blocked because the scanner
 * is misconfigured.
 *
 * <p>Runs at order 2500, after {@link ScanDiffFilter} (order 2300).
 */
@Slf4j
public class SecretScanningFilter extends AbstractGitProxyFilter {

    private static final int ORDER = 2500;

    private final CommitConfig.SecretScanningConfig config;
    private final GitleaksRunner runner;

    public SecretScanningFilter(CommitConfig.SecretScanningConfig config, GitleaksRunner runner) {
        super(ORDER, Set.of(HttpOperation.PUSH));
        this.config = config;
        this.runner = runner;
    }

    /** Convenience constructor for wiring from {@code GitProxyServletRegistrar}. */
    public SecretScanningFilter(CommitConfig.SecretScanningConfig config) {
        this(config, new GitleaksRunner());
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!config.isEnabled()) {
            log.debug("Secret scanning disabled — skipping");
            return;
        }

        var requestDetails = (GitRequestDetails) request.getAttribute(GIT_REQUEST_ATTR);
        if (requestDetails == null) {
            log.warn("GitRequestDetails not found in request attributes");
            return;
        }

        // Re-use the diff already generated and stored by ScanDiffFilter
        String diff = requestDetails.getSteps().stream()
                .filter(s -> DiffGenerationHook.STEP_NAME_PUSH_DIFF.equals(s.getStepName()))
                .findFirst()
                .map(PushStep::getContent)
                .orElse(null);

        if (diff == null || diff.isBlank()) {
            log.debug("No diff available for secret scanning — skipping");
            return;
        }

        Optional<List<GitleaksRunner.Finding>> result = runner.scan(diff, config);

        if (result.isEmpty()) {
            // Fail-open: scanner unavailable or errored — already logged by GitleaksRunner
            return;
        }

        List<GitleaksRunner.Finding> findings = result.get();
        if (findings.isEmpty()) {
            log.debug("Secret scan passed — no findings");
            return;
        }

        log.warn("Secret scan found {} finding(s)", findings.size());
        String findingList = findings.stream()
                .map(f -> sym(CROSS_MARK) + "  " + f.toMessage())
                .collect(Collectors.joining("\n\n"));
        String title = sym(NO_ENTRY) + "  Push Blocked — " + findings.size() + " Secret(s) Detected";
        String body = "Secret scan findings:\n\n" + findingList;
        recordIssue(request, findings.size() + " secret(s) detected", GitClient.format(title, body, RED, null));
    }
}
