package org.finos.gitproxy.git;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.finos.gitproxy.db.model.PushStep;
import org.finos.gitproxy.db.model.StepStatus;

/**
 * Pre-receive hook that validates incoming pushes and sends sideband feedback to the client. Runs after JGit has
 * received the pack data but before refs are updated in the local repository.
 *
 * <p>Objects are already stored in the local repo at this point, so {@link CommitInspectionService} can inspect commits
 * directly.
 */
@Slf4j
public class ProxyPreReceiveHook implements GitProxyHook {

    private static final int ORDER = 270;

    private final PushContext pushContext;

    public ProxyPreReceiveHook(PushContext pushContext) {
        this.pushContext = pushContext;
    }

    @Override
    public void onPreReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
        Repository repo = rp.getRepository();
        List<String> logs = new ArrayList<>();

        for (ReceiveCommand cmd : commands) {
            String refName = cmd.getRefName();
            String oldId = cmd.getOldId().abbreviate(7).name();
            String newId = cmd.getNewId().abbreviate(7).name();

            logs.add(cmd.getType() + " " + refName + " " + oldId + " -> " + newId);

            if (cmd.getType() == ReceiveCommand.Type.DELETE) {
                logs.add("Skipped inspection: ref deletion");
                continue;
            }

            try {
                inspectCommits(rp, repo, cmd, logs);
            } catch (Exception e) {
                log.error("Failed to inspect commits for {}", refName, e);
                logs.add("ERROR inspecting " + refName + ": " + e.getMessage());
            }
        }

        pushContext.addStep(PushStep.builder()
                .stepName("inspection")
                .stepOrder(ORDER)
                .status(StepStatus.PASS)
                .logs(logs)
                .build());
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public String getName() {
        return "ProxyPreReceiveHook";
    }

    private void inspectCommits(ReceivePack rp, Repository repo, ReceiveCommand cmd, List<String> logs)
            throws Exception {
        String fromCommit = cmd.getOldId().name();
        String toCommit = cmd.getNewId().name();

        // For new branches (old is zero ID), we can't do a range - just inspect the tip
        if (ObjectId.zeroId().equals(cmd.getOldId())) {
            Commit tipCommit = CommitInspectionService.getCommitDetails(repo, toCommit);
            String tipLine =
                    "New branch - tip commit by " + tipCommit.getAuthor().getName() + " <"
                            + tipCommit.getAuthor().getEmail() + ">";
            logs.add(tipLine);
            return;
        }

        List<Commit> commits = CommitInspectionService.getCommitRange(repo, fromCommit, toCommit);
        for (Commit commit : commits) {
            String shortSha = commit.getSha().substring(0, 7);
            String firstLine = commit.getMessage().lines().findFirst().orElse("(empty)");
            logs.add(shortSha + " " + commit.getAuthor().getName() + " <"
                    + commit.getAuthor().getEmail() + "> " + firstLine);
        }
    }
}
