package org.finos.gitproxy.git;

import static org.finos.gitproxy.git.GitClient.AnsiColor.*;
import static org.finos.gitproxy.git.GitClient.SymbolCodes.*;

import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;

/**
 * Terminal pre-receive hook that reads the shared {@link ValidationContext} and rejects all commands if any validation
 * issues were found. Sends a sideband summary of all issues so the user sees every problem in a single push attempt.
 *
 * <p>This hook must run after all validation hooks and before any informational hooks (e.g. ProxyPreReceiveHook).
 */
@Slf4j
@RequiredArgsConstructor
public class ValidationVerifierHook implements PreReceiveHook {

    private final ValidationContext validationContext;

    @Override
    public void onPreReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
        if (!validationContext.hasIssues()) {
            rp.sendMessage(GREEN + "[git-proxy] " + HEAVY_CHECK_MARK.emoji() + "  All checks passed" + RESET);
            return;
        }

        List<ValidationContext.ValidationIssue> issues = validationContext.getIssues();

        rp.sendMessage("");
        rp.sendMessage(RED + "[git-proxy] " + NO_ENTRY.emoji() + "  Push rejected — " + issues.size()
                + " issue(s) found:" + RESET);
        rp.sendMessage("");

        for (int i = 0; i < issues.size(); i++) {
            var issue = issues.get(i);
            rp.sendMessage(
                    RED + "[git-proxy]   " + (i + 1) + ". [" + issue.hookName() + "] " + issue.summary() + RESET);
            if (issue.detail() != null && !issue.detail().isEmpty()) {
                rp.sendMessage(YELLOW + "[git-proxy]      " + issue.detail() + RESET);
            }
        }

        rp.sendMessage("");
        rp.sendMessage(YELLOW + "[git-proxy] " + WARNING.emoji() + "  Fix all issues and push again" + RESET);

        // Reject all commands
        String rejectMessage = issues.size() + " validation issue(s) — see above";
        for (ReceiveCommand cmd : commands) {
            if (cmd.getResult() == ReceiveCommand.Result.NOT_ATTEMPTED) {
                cmd.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, rejectMessage);
            }
        }

        log.info("Push rejected with {} validation issue(s)", issues.size());
    }
}
