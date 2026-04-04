package org.finos.gitproxy.git;

import static org.finos.gitproxy.git.GitClientUtils.AnsiColor.*;
import static org.finos.gitproxy.git.GitClientUtils.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClientUtils.color;
import static org.finos.gitproxy.git.GitClientUtils.sym;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.finos.gitproxy.db.model.PushStep;
import org.finos.gitproxy.db.model.StepStatus;

/**
 * Pre-receive hook that detects "hidden" commits - objects received in the push pack that are not part of the
 * explicitly introduced commit range. This catches the case where a branch was created from commits that have not yet
 * been approved and pushed to the remote, smuggling unapproved history through as pack filler.
 *
 * <p>Algorithm:
 *
 * <ol>
 *   <li><b>introduced</b> - commits reachable from each {@code newId} up to (not including) its {@code oldId}, across
 *       all pushed commands; computed via {@link CommitInspectionService#getCommitRange}.
 *   <li><b>allNew</b> - commits reachable from any pushed {@code newId} that are not reachable from any existing ref
 *       (i.e., genuinely new to this repository); computed via {@link RevWalk}.
 *   <li><b>hidden</b> = {@code allNew} ∖ {@code introduced} - commits in the pack but outside the pushed range.
 * </ol>
 *
 * <p>This hook short-circuits the chain immediately on failure (direct rejection, not via {@link ValidationContext}).
 */
@Slf4j
@RequiredArgsConstructor
public class CheckHiddenCommitsHook implements GitProxyHook {

    private static final int ORDER = 220;
    private static final String STEP_NAME = "checkHiddenCommits";

    private final PushContext pushContext;

    public void onPreReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
        Repository repo = rp.getRepository();

        try {
            Set<String> allIntroduced = collectIntroducedCommits(repo, commands);
            Set<String> allNew = collectAllNewCommits(repo, commands);

            Set<String> hidden = new HashSet<>(allNew);
            hidden.removeAll(allIntroduced);

            if (hidden.isEmpty()) {
                log.debug("checkHiddenCommits: all {} new commit(s) are within the introduced range", allNew.size());
                if (pushContext != null) {
                    pushContext.addStep(PushStep.builder()
                            .stepName(STEP_NAME)
                            .stepOrder(ORDER)
                            .status(StepStatus.PASS)
                            .build());
                }
                return;
            }

            String msg = "Unreferenced commits in pack (" + hidden.size() + "): "
                    + String.join(", ", hidden) + ".\n"
                    + "This usually happens when a branch was made from a commit that hasn't been approved"
                    + " and pushed to the remote.\n"
                    + "Please get approval on the commits, push them and try again.";

            rp.sendMessage(color(RED, "" + sym(NO_ENTRY) + "  Push blocked - hidden commits detected"));
            rp.sendMessage(color(YELLOW, "  " + sym(WARNING) + "  " + msg));

            for (ReceiveCommand cmd : commands) {
                if (cmd.getResult() == ReceiveCommand.Result.NOT_ATTEMPTED) {
                    cmd.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, "Hidden commits detected");
                }
            }

        } catch (Exception e) {
            log.error("Failed to check hidden commits", e);
        }
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public String getName() {
        return "CheckHiddenCommitsHook";
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
                // Dereference annotated tags to their target commit before walking
                ObjectId commitId = repo.resolve(cmd.getNewId().name() + "^{commit}");
                if (commitId == null) continue; // non-commit object (blob/tree tag) — skip
                walk.markStart(walk.parseCommit(commitId));
            }

            for (Ref ref : repo.getRefDatabase().getRefsByPrefix("refs/")) {
                ObjectId id = ref.getObjectId();
                if (id == null) continue;
                try {
                    walk.markUninteresting(walk.parseCommit(id));
                } catch (Exception e) {
                    // Not a commit (tag pointing to a blob/tree, etc.) - skip
                }
            }

            for (RevCommit commit : walk) {
                result.add(commit.getName());
            }
        }

        return result;
    }
}
