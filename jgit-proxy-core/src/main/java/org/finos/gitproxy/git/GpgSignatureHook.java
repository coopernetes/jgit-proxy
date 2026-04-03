package org.finos.gitproxy.git;

import static org.finos.gitproxy.git.GitClientUtils.AnsiColor.*;
import static org.finos.gitproxy.git.GitClientUtils.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClientUtils.color;
import static org.finos.gitproxy.git.GitClientUtils.sym;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.finos.gitproxy.config.GpgConfig;
import org.finos.gitproxy.db.model.PushStep;
import org.finos.gitproxy.db.model.StepStatus;
import org.finos.gitproxy.validation.GpgSignatureCheck;
import org.finos.gitproxy.validation.Violation;

/**
 * S&F-mode adapter for {@link GpgSignatureCheck}. Reads commits from the JGit repository, sends per-violation sideband
 * feedback, and records results in the shared {@link ValidationContext} and {@link PushContext}.
 */
@Slf4j
@RequiredArgsConstructor
public class GpgSignatureHook implements PreReceiveHook {

    private static final int STEP_ORDER = 2400;

    private final GpgConfig config;
    private final ValidationContext validationContext;
    private final PushContext pushContext;

    @Override
    public void onPreReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
        var check = new GpgSignatureCheck(config);
        Repository repo = rp.getRepository();
        List<Violation> allViolations = new ArrayList<>();

        for (ReceiveCommand cmd : commands) {
            if (cmd.getType() == ReceiveCommand.Type.DELETE) continue;
            try {
                List<Violation> violations = check.check(getCommits(repo, cmd));
                for (Violation v : violations) {
                    rp.sendMessage(color(RED, "" + sym(CROSS_MARK) + "  " + v.subject() + " — " + v.reason()));
                    validationContext.addIssue("GpgSignatureHook", v.reason(), v.formattedDetail());
                    allViolations.add(v);
                }
            } catch (Exception e) {
                log.error("Failed to check GPG signatures for {}", cmd.getRefName(), e);
                rp.sendMessage(color(YELLOW, "" + sym(WARNING) + "  Could not check GPG signature: " + e.getMessage()));
            }
        }

        if (allViolations.isEmpty()) {
            pushContext.addStep(PushStep.builder()
                    .stepName("checkSignatures")
                    .stepOrder(STEP_ORDER)
                    .status(StepStatus.PASS)
                    .build());
        }
    }

    private List<Commit> getCommits(Repository repo, ReceiveCommand cmd) throws Exception {
        if (ObjectId.zeroId().equals(cmd.getOldId())) {
            return List.of(CommitInspectionService.getCommitDetails(
                    repo, cmd.getNewId().name()));
        }
        return CommitInspectionService.getCommitRange(
                repo, cmd.getOldId().name(), cmd.getNewId().name());
    }
}
