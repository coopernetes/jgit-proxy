package org.finos.gitproxy.git;

import static org.finos.gitproxy.git.GitClient.AnsiColor.*;
import static org.finos.gitproxy.git.GitClient.SymbolCodes.*;

import java.io.IOException;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.finos.gitproxy.db.model.PushStep;
import org.finos.gitproxy.db.model.StepStatus;

/**
 * Pre-receive hook that generates unified diffs for incoming pushes and stores them in a {@link PushContext} for later
 * persistence.
 *
 * <p>Two types of diffs are generated:
 *
 * <ul>
 *   <li><b>Push diff</b> ({@code commitFrom..commitTo}): What changed in this specific push. Always generated.
 *   <li><b>Default branch diff</b> ({@code defaultBranch..commitTo}): Total diff of the pushed changes relative to the
 *       repository's default branch. Generated when the push targets a non-default branch. This diff is clearly marked
 *       as auto-generated so the UI can indicate it may not reflect the intended merge target. Users can later request
 *       diffs against a different reference through the UI.
 * </ul>
 *
 * <p>This hook should be placed after validation hooks (so only valid pushes get diffed) but before the persistence
 * result hook.
 */
@Slf4j
@RequiredArgsConstructor
public class DiffGenerationHook implements PreReceiveHook {

    public static final String STEP_NAME_PUSH_DIFF = "diff";
    public static final String STEP_NAME_BRANCH_DIFF = "diff:default-branch";

    private final PushContext pushContext;

    @Override
    public void onPreReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
        Repository repo = rp.getRepository();

        for (ReceiveCommand cmd : commands) {
            if (cmd.getType() == ReceiveCommand.Type.DELETE) {
                continue;
            }

            String refName = cmd.getRefName();
            String commitFrom = cmd.getOldId().name();
            String commitTo = cmd.getNewId().name();
            boolean isNewBranch = ObjectId.zeroId().equals(cmd.getOldId());

            // 1. Push diff: what changed in this push
            generatePushDiff(rp, repo, refName, commitFrom, commitTo, isNewBranch);

            // 2. Default branch diff: total diff relative to the default branch
            //    Only useful when pushing to a non-default branch
            if (!isNewBranch) {
                generateDefaultBranchDiff(rp, repo, refName, commitTo);
            }
        }
    }

    private void generatePushDiff(
            ReceivePack rp,
            Repository repo,
            String refName,
            String commitFrom,
            String commitTo,
            boolean isNewBranch) {
        try {
            String baseLabel = isNewBranch ? "(empty tree)" : commitFrom.substring(0, 7);
            rp.sendMessage(CYAN + "[git-proxy] " + LINK.emoji() + "  Generating push diff "
                    + baseLabel + ".." + commitTo.substring(0, 7) + RESET);

            String diff = CommitInspectionService.getFormattedDiff(repo, commitFrom, commitTo);
            int lines = diff.isEmpty() ? 0 : (int) diff.lines().count();

            rp.sendMessage(CYAN + "[git-proxy]   " + lines + " line(s) of diff" + RESET);

            PushStep step = PushStep.builder()
                    .stepName(STEP_NAME_PUSH_DIFF)
                    .stepOrder(3000)
                    .status(StepStatus.PASS)
                    .content(diff)
                    .build();
            step.getLogs().add("ref: " + refName);
            step.getLogs().add("range: " + commitFrom + ".." + commitTo);
            step.getLogs().add("lines: " + lines);
            step.getLogs().add("type: auto");
            pushContext.addStep(step);
        } catch (IOException e) {
            log.error("Failed to generate push diff for {}", refName, e);
            rp.sendMessage(YELLOW + "[git-proxy]   " + WARNING.emoji()
                    + "  Could not generate push diff: " + e.getMessage() + RESET);
        }
    }

    /**
     * Generate a diff of the pushed commit(s) relative to the repository's default branch. This helps reviewers see
     * the full scope of changes even when the push is part of a larger feature branch.
     */
    private void generateDefaultBranchDiff(
            ReceivePack rp, Repository repo, String pushRef, String commitTo) {
        try {
            String defaultBranch = resolveDefaultBranch(repo);
            if (defaultBranch == null) {
                log.debug("Could not determine default branch, skipping default-branch diff");
                return;
            }

            // Don't generate a redundant diff if pushing to the default branch
            if (pushRef.equals(defaultBranch) || pushRef.equals("refs/heads/" + defaultBranch)) {
                return;
            }

            Ref defaultRef = repo.exactRef(defaultBranch);
            if (defaultRef == null) {
                log.debug("Default branch ref {} not found in repo", defaultBranch);
                return;
            }

            String defaultBranchTip = defaultRef.getObjectId().name();
            String shortBranch = shortenRef(defaultBranch);

            rp.sendMessage(CYAN + "[git-proxy] " + LINK.emoji() + "  Generating diff vs " + shortBranch + RESET);

            String diff = CommitInspectionService.getFormattedDiff(repo, defaultBranchTip, commitTo);
            int lines = diff.isEmpty() ? 0 : (int) diff.lines().count();

            rp.sendMessage(CYAN + "[git-proxy]   " + lines + " line(s) vs " + shortBranch + RESET);

            PushStep step = PushStep.builder()
                    .stepName(STEP_NAME_BRANCH_DIFF)
                    .stepOrder(3001)
                    .status(StepStatus.PASS)
                    .content(diff)
                    .build();
            step.getLogs().add("ref: " + pushRef);
            step.getLogs().add("base: " + defaultBranch + " (" + defaultBranchTip.substring(0, 7) + ")");
            step.getLogs().add("target: " + commitTo);
            step.getLogs().add("lines: " + lines);
            step.getLogs().add("type: auto:default-branch");
            pushContext.addStep(step);
        } catch (IOException e) {
            log.warn("Failed to generate default-branch diff for {}", pushRef, e);
        }
    }

    /**
     * Resolve the default branch. In a bare clone, HEAD is a symbolic ref pointing to the remote's default branch.
     * Falls back to common names if HEAD is detached.
     */
    private String resolveDefaultBranch(Repository repo) throws IOException {
        Ref head = repo.exactRef("HEAD");
        if (head != null && head.isSymbolic()) {
            return head.getTarget().getName();
        }

        for (String candidate : new String[] {"refs/heads/main", "refs/heads/master"}) {
            if (repo.exactRef(candidate) != null) {
                return candidate;
            }
        }

        return null;
    }

    private static String shortenRef(String ref) {
        return ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
    }
}
