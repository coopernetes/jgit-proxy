package org.finos.gitproxy.validation;

import static org.finos.gitproxy.git.GitClient.AnsiColor.*;
import static org.finos.gitproxy.git.GitClient.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClient.sym;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.config.CommitConfig.SecretScanningConfig;
import org.finos.gitproxy.git.GitClient;
import org.finos.gitproxy.git.GitleaksRunner;

/**
 * Scans a unified diff for secrets using gitleaks. Implements {@link DiffCheck} so it can be used in both the
 * transparent-proxy filter chain and the store-and-forward pre-receive hook chain without duplication.
 *
 * <p>Returns {@link Optional#empty()} (fail-open) if the scanner is unavailable or execution fails — pushes are never
 * blocked because the scanner is misconfigured.
 */
@Slf4j
@RequiredArgsConstructor
public class SecretScanCheck implements DiffCheck {

    private final SecretScanningConfig config;
    private final GitleaksRunner runner;

    public SecretScanCheck(SecretScanningConfig config) {
        this(config, new GitleaksRunner());
    }

    @Override
    public Optional<List<Violation>> check(String diff) {
        if (!config.isEnabled()) {
            log.debug("Secret scanning disabled — skipping");
            return Optional.of(List.of());
        }

        if (diff == null || diff.isBlank()) {
            log.debug("No diff available for secret scanning — skipping");
            return Optional.of(List.of());
        }

        Optional<List<GitleaksRunner.Finding>> result = runner.scan(diff, config);

        if (result.isEmpty()) {
            // Fail-open: scanner unavailable or errored — GitleaksRunner already logged the detail
            return Optional.empty();
        }

        List<GitleaksRunner.Finding> findings = result.get();
        if (findings.isEmpty()) {
            return Optional.of(List.of());
        }

        log.warn("Secret scan found {} finding(s)", findings.size());
        String title = sym(NO_ENTRY) + "  Push Blocked — " + findings.size() + " Secret(s) Detected";
        String body = "Secret scan findings:\n\n"
                + findings.stream()
                        .map(f -> sym(CROSS_MARK) + "  " + f.toMessage())
                        .collect(Collectors.joining("\n\n"));

        List<Violation> violations = findings.stream()
                .map(f -> new Violation(f.toMessage(), f.toMessage(), GitClient.format(title, body, RED, null)))
                .collect(Collectors.toList());

        return Optional.of(violations);
    }
}
