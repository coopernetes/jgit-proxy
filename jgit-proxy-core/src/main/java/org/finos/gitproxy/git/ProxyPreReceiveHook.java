package org.finos.gitproxy.git;

import static org.finos.gitproxy.git.GitClient.AnsiColor.*;
import static org.finos.gitproxy.git.GitClient.SymbolCodes.*;

import java.util.Collection;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;

/**
 * Pre-receive hook that validates incoming pushes and sends sideband feedback to the client. Runs after JGit has
 * received the pack data but before refs are updated in the local repository.
 *
 * <p>Objects are already stored in the local repo at this point, so {@link CommitInspectionService} can inspect commits
 * directly.
 */
@Slf4j
public class ProxyPreReceiveHook implements PreReceiveHook {

    @Override
    public void onPreReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
        rp.sendMessage(CYAN + "[git-proxy] " + LINK.emoji() + "  Validating push..." + RESET);

        Repository repo = rp.getRepository();

        for (ReceiveCommand cmd : commands) {
            String refName = cmd.getRefName();
            String oldId = cmd.getOldId().abbreviate(7).name();
            String newId = cmd.getNewId().abbreviate(7).name();

            rp.sendMessage(
                    BLUE + "[git-proxy]   " + cmd.getType() + " " + refName + " " + oldId + " -> " + newId + RESET);

            if (cmd.getType() == ReceiveCommand.Type.DELETE) {
                rp.sendMessage(
                        YELLOW + "[git-proxy]   " + WARNING.emoji() + "  Ref deletion, skipping inspection" + RESET);
                continue;
            }

            try {
                inspectCommits(rp, repo, cmd);
            } catch (Exception e) {
                log.error("Failed to inspect commits for {}", refName, e);
                rp.sendMessage(RED + "[git-proxy]   " + CROSS_MARK.emoji() + "  Commit inspection failed: "
                        + e.getMessage() + RESET);
            }
        }

        rp.sendMessage(GREEN + "[git-proxy] " + HEAVY_CHECK_MARK.emoji() + "  Validation complete" + RESET);
    }

    private void inspectCommits(ReceivePack rp, Repository repo, ReceiveCommand cmd) throws Exception {
        String fromCommit = cmd.getOldId().name();
        String toCommit = cmd.getNewId().name();

        // For new branches (old is zero ID), we can't do a range — just inspect the tip
        if (ObjectId.zeroId().equals(cmd.getOldId())) {
            Commit tipCommit = CommitInspectionService.getCommitDetails(repo, toCommit);
            rp.sendMessage(CYAN + "[git-proxy]   New branch - tip commit by "
                    + tipCommit.getAuthor().getName() + " <"
                    + tipCommit.getAuthor().getEmail() + ">" + RESET);
            return;
        }

        List<Commit> commits = CommitInspectionService.getCommitRange(repo, fromCommit, toCommit);
        rp.sendMessage(CYAN + "[git-proxy]   " + commits.size() + " commit(s) in push" + RESET);

        for (Commit commit : commits) {
            String shortSha = commit.getSha().substring(0, 7);
            String firstLine = commit.getMessage().lines().findFirst().orElse("(empty)");
            rp.sendMessage(MAGENTA + "[git-proxy]     " + shortSha + " "
                    + commit.getAuthor().getName() + " <" + commit.getAuthor().getEmail() + "> " + firstLine + RESET);
        }
    }
}
