package org.finos.gitproxy.git;

import static org.finos.gitproxy.git.GitClient.AnsiColor.*;
import static org.finos.gitproxy.git.GitClient.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClient.color;
import static org.finos.gitproxy.git.GitClient.sym;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.finos.gitproxy.db.model.PushStep;
import org.finos.gitproxy.db.model.StepStatus;

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

    private final PushContext pushContext;

    public SlowApprovalPreReceiveHook(PushContext pushContext) {
        this.pushContext = pushContext;
    }

    @Override
    public void onPreReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
        OutputStream msgOut = rp.getMessageOutputStream();

        sendAndFlush(rp, msgOut, color(CYAN, "" + sym(KEY) + "  Push received, submitting for approval..."));
        sendAndFlush(
                rp,
                msgOut,
                color(
                        YELLOW,
                        "" + sym(WARNING)
                                + String.format("  Waiting for external approval (%ds timeout)...", TOTAL_SECONDS)));

        for (int elapsed = 0; elapsed < TOTAL_SECONDS; elapsed += INTERVAL_SECONDS) {
            try {
                Thread.sleep(INTERVAL_SECONDS * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sendAndFlush(rp, msgOut, color(RED, "" + sym(CROSS_MARK) + "  Approval check interrupted"));
                pushContext.addStep(PushStep.builder()
                        .stepName("approval")
                        .status(StepStatus.FAIL)
                        .errorMessage("Approval check interrupted")
                        .logs(List.of("Interrupted after " + elapsed + "s"))
                        .build());
                return;
            }

            int remaining = TOTAL_SECONDS - elapsed - INTERVAL_SECONDS;
            if (remaining > 0) {
                sendAndFlush(
                        rp,
                        msgOut,
                        color(
                                YELLOW,
                                "" + sym(WARNING)
                                        + String.format("  Still waiting for approval... (%ds remaining)", remaining)));
            }
        }

        sendAndFlush(rp, msgOut, color(GREEN, "" + sym(HEAVY_CHECK_MARK) + "  Approval granted"));
        pushContext.addStep(PushStep.builder()
                .stepName("approval")
                .status(StepStatus.PASS)
                .logs(List.of("Approval granted after " + TOTAL_SECONDS + "s"))
                .build());
    }

    /** Send a sideband message and flush immediately so it streams to the git client over HTTP. */
    private void sendAndFlush(ReceivePack rp, OutputStream msgOut, String message) {
        rp.sendMessage(message);
        try {
            msgOut.flush();
        } catch (IOException e) {
            log.warn("Failed to flush sideband message", e);
        }
    }
}
