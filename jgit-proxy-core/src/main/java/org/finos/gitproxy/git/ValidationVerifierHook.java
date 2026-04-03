package org.finos.gitproxy.git;

import static org.finos.gitproxy.git.GitClient.AnsiColor.*;
import static org.finos.gitproxy.git.GitClient.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClient.color;
import static org.finos.gitproxy.git.GitClient.sym;

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
            rp.sendMessage(color(GREEN, "" + sym(HEAVY_CHECK_MARK) + "  All checks passed"));
            return;
        }

        List<ValidationContext.ValidationIssue> issues = validationContext.getIssues();

        rp.sendMessage("");
        rp.sendMessage(color(RED, "" + sym(NO_ENTRY) + "  Push rejected — " + issues.size() + " issue(s) found:"));
        rp.sendMessage("");

        for (int i = 0; i < issues.size(); i++) {
            var issue = issues.get(i);
            rp.sendMessage(color(RED, "  " + (i + 1) + ". [" + issue.hookName() + "] " + issue.summary()));
            if (issue.detail() != null && !issue.detail().isEmpty()) {
                rp.sendMessage(color(YELLOW, "     " + issue.detail()));
            }
        }

        rp.sendMessage("");
        rp.sendMessage(color(YELLOW, "" + sym(WARNING) + "  Fix all issues and push again"));

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
