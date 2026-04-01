package org.finos.gitproxy.git;

import static org.finos.gitproxy.git.GitClient.AnsiColor.*;
import static org.finos.gitproxy.git.GitClient.SymbolCodes.*;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;

/**
 * Pre-receive hook that detects "hidden" commits — objects received in the push pack that are not part of the
 * explicitly introduced commit range. This catches the case where a branch was created from commits that have not yet
 * been approved and pushed to the remote, smuggling unapproved history through as pack filler.
 *
 * <p>Algorithm:
 *
 * <ol>
 *   <li><b>introduced</b> — commits reachable from each {@code newId} up to (not including) its {@code oldId}, across
 *       all pushed commands; computed via {@link CommitInspectionService#getCommitRange}.
 *   <li><b>allNew</b> — commits reachable from any pushed {@code newId} that are not reachable from any existing ref
 *       (i.e., genuinely new to this repository); computed via {@link RevWalk}.
 *   <li><b>hidden</b> = {@code allNew} ∖ {@code introduced} — commits in the pack but outside the pushed range.
 * </ol>
 *
 * <p>This hook short-circuits the chain immediately on failure (direct rejection, not via {@link ValidationContext}).
 */
@Slf4j
public class CheckHiddenCommitsHook implements PreReceiveHook {

    @Override
    public void onPreReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
        Repository repo = rp.getRepository();

        try {
            Set<String> allIntroduced = collectIntroducedCommits(repo, commands);
            Set<String> allNew = collectAllNewCommits(repo, commands);

            Set<String> hidden = new HashSet<>(allNew);
            hidden.removeAll(allIntroduced);

            if (hidden.isEmpty()) {
                log.debug("checkHiddenCommits: all {} new commit(s) are within the introduced range", allNew.size());
                return;
            }

            String msg = "Unreferenced commits in pack (" + hidden.size() + "): "
                    + String.join(", ", hidden) + ".\n"
                    + "This usually happens when a branch was made from a commit that hasn't been approved"
                    + " and pushed to the remote.\n"
                    + "Please get approval on the commits, push them and try again.";

            rp.sendMessage(
                    RED + "[git-proxy] " + NO_ENTRY.emoji() + "  Push blocked — hidden commits detected" + RESET);
            rp.sendMessage(YELLOW + "[git-proxy]   " + WARNING.emoji() + "  " + msg + RESET);

            for (ReceiveCommand cmd : commands) {
                if (cmd.getResult() == ReceiveCommand.Result.NOT_ATTEMPTED) {
                    cmd.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, "Hidden commits detected");
                }
            }

        } catch (Exception e) {
            log.error("Failed to check hidden commits", e);
        }
    }

    private Set<String> collectIntroducedCommits(Repository repo, Collection<ReceiveCommand> commands)
            throws Exception {
        Set<String> introduced = new HashSet<>();
        for (ReceiveCommand cmd : commands) {
            if (cmd.getResult() != ReceiveCommand.Result.NOT_ATTEMPTED) continue;
            if (cmd.getType() == ReceiveCommand.Type.DELETE) continue;
            CommitInspectionService.getCommitRange(
                            repo, cmd.getOldId().name(), cmd.getNewId().name())
                    .stream()
                    .map(Commit::getSha)
                    .forEach(introduced::add);
        }
        return introduced;
    }

    /**
     * Collect all commits reachable from any pushed tip that are not reachable from any existing ref. These are the
     * commits that were genuinely new to this repository as part of the pack.
     */
    private Set<String> collectAllNewCommits(Repository repo, Collection<ReceiveCommand> commands) throws IOException {
        Set<String> result = new HashSet<>();

        try (RevWalk walk = new RevWalk(repo)) {
            for (ReceiveCommand cmd : commands) {
                if (cmd.getResult() != ReceiveCommand.Result.NOT_ATTEMPTED) continue;
                if (cmd.getType() == ReceiveCommand.Type.DELETE) continue;
                walk.markStart(walk.parseCommit(cmd.getNewId()));
            }

            for (Ref ref : repo.getRefDatabase().getRefsByPrefix("refs/")) {
                ObjectId id = ref.getObjectId();
                if (id == null) continue;
                try {
                    walk.markUninteresting(walk.parseCommit(id));
                } catch (Exception e) {
                    // Not a commit (tag pointing to a blob/tree, etc.) — skip
                }
            }

            for (RevCommit commit : walk) {
                result.add(commit.getName());
            }
        }

        return result;
    }
}
