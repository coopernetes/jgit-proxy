package org.finos.gitproxy.git;

import static org.finos.gitproxy.git.GitClient.AnsiColor.*;
import static org.finos.gitproxy.git.GitClient.SymbolCodes.*;

import java.util.Collection;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;

/**
 * Demonstration pre-receive hook that simulates a slow external approval workflow (e.g. ServiceNow ticket creation,
 * risk ledger check). Blocks for 15 seconds total, sending a sideband progress message every 5 seconds to keep the
 * connection alive and provide user feedback.
 *
 * <p>This is the pattern that regulated environments would use for shelling out to approval systems — the sideband
 * messages keep the git client from timing out and let the user see what's happening.
 */
@Slf4j
public class SlowApprovalPreReceiveHook implements PreReceiveHook {

    private static final int TOTAL_SECONDS = 15;
    private static final int INTERVAL_SECONDS = 5;

    @Override
    public void onPreReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
        rp.sendMessage(CYAN + "[git-proxy] " + KEY.emoji() + "  Push received, submitting for approval..." + RESET);
        rp.sendMessage(YELLOW + "[git-proxy] " + WARNING.emoji()
                + String.format("  Waiting for external approval (%ds timeout)...", TOTAL_SECONDS)
                + RESET);

        for (int elapsed = 0; elapsed < TOTAL_SECONDS; elapsed += INTERVAL_SECONDS) {
            try {
                Thread.sleep(INTERVAL_SECONDS * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                rp.sendMessage(RED + "[git-proxy] " + CROSS_MARK.emoji() + "  Approval check interrupted" + RESET);
                return;
            }

            int remaining = TOTAL_SECONDS - elapsed - INTERVAL_SECONDS;
            if (remaining > 0) {
                rp.sendMessage(YELLOW + "[git-proxy] " + WARNING.emoji()
                        + String.format("  Still waiting for approval... (%ds remaining)", remaining) + RESET);
            }
        }

        rp.sendMessage(GREEN + "[git-proxy] " + HEAVY_CHECK_MARK.emoji() + "  Approval granted" + RESET);
    }
}
