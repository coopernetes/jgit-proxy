package org.finos.gitproxy.git;

import static org.finos.gitproxy.git.GitClientUtils.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClientUtils.sym;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.db.model.PushStep;
import org.finos.gitproxy.db.model.StepStatus;
import org.finos.gitproxy.validation.Violation;

/**
 * S&amp;F-mode secret scanning hook. Runs {@code gitleaks git} directly against the JGit bare repository so that
 * path-based allowlists and per-file context in gitleaks rules are applied correctly.
 *
 * <p>The commit range is derived from each {@link ReceiveCommand}: new-branch pushes use {@code commitTo --not --all}
 * to limit scanning to commits not already reachable from any existing ref; branch updates use
 * {@code commitFrom..commitTo}.
 *
 * <p>If the scanner is unavailable the hook continues (fail-open), recording a SKIPPED step.
 */
@Slf4j
@RequiredArgsConstructor
public class SecretScanningHook implements PreReceiveHook {

    private static final int STEP_ORDER = 2500;
    private static final String STEP_NAME = "scanSecrets";

    private final CommitConfig.SecretScanningConfig config;
    private final ValidationContext validationContext;
    private final PushContext pushContext;
    private final GitleaksRunner runner;

    public SecretScanningHook(
            CommitConfig.SecretScanningConfig config, ValidationContext validationContext, PushContext pushContext) {
        this(config, validationContext, pushContext, new GitleaksRunner());
    }

    @Override
    public void onPreReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
        Path repoDir = rp.getRepository().getDirectory().toPath();

        List<Violation> allViolations = new ArrayList<>();
        boolean scannerUnavailable = false;

        for (ReceiveCommand cmd : commands) {
            if (cmd.getType() == ReceiveCommand.Type.DELETE) {
                continue;
            }

            String commitFrom = cmd.getOldId().name();
            String commitTo = cmd.getNewId().name();

            Optional<List<GitleaksRunner.Finding>> result = runner.scanGit(repoDir, commitFrom, commitTo, config);

            if (result.isEmpty()) {
                // Fail-open: scanner unavailable or errored — GitleaksRunner already logged the detail
                scannerUnavailable = true;
                continue;
            }

            for (GitleaksRunner.Finding f : result.get()) {
                String msg = f.toMessage();
                allViolations.add(new Violation(msg, msg, sym(CROSS_MARK) + "  " + msg));
            }
        }

        if (scannerUnavailable && allViolations.isEmpty()) {
            pushContext.addStep(PushStep.builder()
                    .stepName(STEP_NAME)
                    .stepOrder(STEP_ORDER)
                    .status(StepStatus.SKIPPED)
                    .build());
            return;
        }

        if (allViolations.isEmpty()) {
            pushContext.addStep(PushStep.builder()
                    .stepName(STEP_NAME)
                    .stepOrder(STEP_ORDER)
                    .status(StepStatus.PASS)
                    .build());
            return;
        }

        for (Violation v : allViolations) {
            validationContext.addIssue(STEP_NAME, v.reason(), v.formattedDetail());
        }
    }
}
