package org.finos.gitproxy.git;

import static org.finos.gitproxy.git.GitClient.AnsiColor.*;
import static org.finos.gitproxy.git.GitClient.SymbolCodes.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.db.model.PushStep;
import org.finos.gitproxy.db.model.StepStatus;

/**
 * Pre-receive hook that validates commit messages against configured blocked patterns and literals. Reports results via
 * sideband and records any issues in the shared {@link ValidationContext} — does not reject commands directly.
 */
@Slf4j
@RequiredArgsConstructor
public class CommitMessageValidationHook implements PreReceiveHook {

    private final CommitConfig commitConfig;
    private final ValidationContext validationContext;
    private final PushContext pushContext;

    @Override
    public void onPreReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
        rp.sendMessage(CYAN + "[git-proxy] " + LINK.emoji() + "  Checking commit messages..." + RESET);

        Repository repo = rp.getRepository();
        List<String> logs = new ArrayList<>();
        boolean anyFailed = false;

        for (ReceiveCommand cmd : commands) {
            if (cmd.getType() == ReceiveCommand.Type.DELETE) {
                continue;
            }

            try {
                List<Commit> commits = getCommits(repo, cmd);

                for (Commit commit : commits) {
                    String shortSha = commit.getSha().substring(0, 7);
                    String firstLine = commit.getMessage().lines().findFirst().orElse("(empty)");
                    String reason = findBlockedReason(commit.getMessage());

                    if (reason != null) {
                        validationContext.addIssue(
                                "CommitMessage", shortSha + " — blocked: " + reason, "Message: " + firstLine);
                        rp.sendMessage(RED + "[git-proxy]   " + CROSS_MARK.emoji() + "  " + shortSha + " " + firstLine
                                + RESET);
                        rp.sendMessage(RED + "[git-proxy]     " + reason + RESET);
                        logs.add("FAIL: " + shortSha + " — " + reason + " [" + firstLine + "]");
                        anyFailed = true;
                    } else {
                        rp.sendMessage(GREEN + "[git-proxy]   " + HEAVY_CHECK_MARK.emoji() + "  " + shortSha + " "
                                + firstLine + RESET);
                        logs.add("PASS: " + shortSha + " — " + firstLine);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to validate commit messages for {}", cmd.getRefName(), e);
                rp.sendMessage(YELLOW + "[git-proxy]   " + WARNING.emoji() + "  Could not validate messages: "
                        + e.getMessage() + RESET);
                logs.add("ERROR: " + cmd.getRefName() + " — " + e.getMessage());
            }
        }

        // Only add a summary step when passing — failures are already captured as per-issue
        // steps by ValidationContext and stored by validationResultHook, so a summary FAIL
        // step here would duplicate those entries in the UI.
        if (!anyFailed) {
            pushContext.addStep(PushStep.builder()
                    .stepName("checkCommitMessages")
                    .status(StepStatus.PASS)
                    .logs(logs)
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

    private String findBlockedReason(String message) {
        if (message == null || message.isEmpty()) {
            return "empty commit message";
        }

        List<String> literals = commitConfig.getMessage().getBlock().getLiterals();
        for (String literal : literals) {
            if (message.toLowerCase().contains(literal.toLowerCase())) {
                return "contains blocked term: \"" + literal + "\"";
            }
        }

        List<Pattern> patterns = commitConfig.getMessage().getBlock().getPatterns();
        for (Pattern pattern : patterns) {
            if (pattern.matcher(message).find()) {
                return "matches blocked pattern: " + pattern.pattern();
            }
        }

        return null;
    }
}
