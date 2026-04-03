package org.finos.gitproxy.git;

import static org.finos.gitproxy.git.GitClientUtils.AnsiColor.*;
import static org.finos.gitproxy.git.GitClientUtils.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClientUtils.color;
import static org.finos.gitproxy.git.GitClientUtils.sym;

import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.finos.gitproxy.db.model.PushStep;
import org.finos.gitproxy.db.model.StepStatus;

/**
 * Pre-receive hook that rejects pushes where no commits can be found in the pushed range. Two cases are distinguished:
 *
 * <ul>
 *   <li><b>New branch with no new commits</b> — the branch tip resolves to an existing commit already reachable from
 *       another ref; the developer pushed an empty branch pointer with no new work.
 *   <li><b>Existing branch with no new commits</b> — commit data could not be resolved; this usually indicates a proxy
 *       configuration or repository state problem.
 * </ul>
 *
 * <p>This hook short-circuits the chain immediately on failure (direct rejection, not via {@link ValidationContext}).
 */
@Slf4j
@RequiredArgsConstructor
public class CheckEmptyBranchHook implements PreReceiveHook {

    private static final int STEP_ORDER = 2050;
    private static final String STEP_NAME = "checkEmptyBranch";

    private final PushContext pushContext;

    public void onPreReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
        Repository repo = rp.getRepository();

        for (ReceiveCommand cmd : commands) {
            if (cmd.getResult() != ReceiveCommand.Result.NOT_ATTEMPTED) continue;
            if (cmd.getType() == ReceiveCommand.Type.DELETE) continue;

            try {
                List<Commit> commits = getCommits(repo, cmd);
                if (!commits.isEmpty()) continue;

                String msg;
                if (ObjectId.zeroId().equals(cmd.getOldId())) {
                    msg = "Push blocked: Empty branch. Please make a commit before pushing a new branch.";
                } else {
                    msg = "Push blocked: Commit data not found. Please contact an administrator for support.";
                }

                rp.sendMessage(color(RED, "" + sym(NO_ENTRY) + "  " + msg));
                cmd.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, msg);
                // Chain stops once a command is rejected; remaining commands will be skipped
                return;

            } catch (Exception e) {
                log.error("Failed to check empty branch for {}", cmd.getRefName(), e);
            }
        }

        if (pushContext != null) {
            pushContext.addStep(PushStep.builder()
                    .stepName(STEP_NAME)
                    .stepOrder(STEP_ORDER)
                    .status(StepStatus.PASS)
                    .build());
        }
    }

    private List<Commit> getCommits(Repository repo, ReceiveCommand cmd) throws Exception {
        return CommitInspectionService.getCommitRange(
                repo, cmd.getOldId().name(), cmd.getNewId().name());
    }
}
