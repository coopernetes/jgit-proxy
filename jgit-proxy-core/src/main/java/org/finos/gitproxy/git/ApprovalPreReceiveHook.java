package org.finos.gitproxy.git;

import static org.finos.gitproxy.git.GitClientUtils.AnsiColor.*;
import static org.finos.gitproxy.git.GitClientUtils.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClientUtils.color;
import static org.finos.gitproxy.git.GitClientUtils.sym;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Collection;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.finos.gitproxy.approval.ApprovalGateway;
import org.finos.gitproxy.approval.ApprovalResult;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.model.PushStatus;

/**
 * Pre-receive hook that implements the approval gate. For clean pushes (no validation issues) it passes immediately.
 * For blocked pushes it delegates to an {@link ApprovalGateway} - which by default polls the push store waiting for a
 * human reviewer to approve or reject via the UI/API.
 *
 * <p>This hook must run AFTER {@code PushStorePersistenceHook.validationResultHook()} so the validation record is
 * already persisted and its ID is stored in the repo config.
 */
@Slf4j
public class ApprovalPreReceiveHook implements PreReceiveHook {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);

    private final PushStore pushStore;
    private final ApprovalGateway approvalGateway;
    private final Duration timeout;
    private final String serviceUrl;

    public ApprovalPreReceiveHook(PushStore pushStore, ApprovalGateway approvalGateway) {
        this(pushStore, approvalGateway, DEFAULT_TIMEOUT, null);
    }

    public ApprovalPreReceiveHook(PushStore pushStore, ApprovalGateway approvalGateway, String serviceUrl) {
        this(pushStore, approvalGateway, DEFAULT_TIMEOUT, serviceUrl);
    }

    public ApprovalPreReceiveHook(PushStore pushStore, ApprovalGateway approvalGateway, Duration timeout) {
        this(pushStore, approvalGateway, timeout, null);
    }

    public ApprovalPreReceiveHook(
            PushStore pushStore, ApprovalGateway approvalGateway, Duration timeout, String serviceUrl) {
        this.pushStore = pushStore;
        this.approvalGateway = approvalGateway;
        this.timeout = timeout;
        this.serviceUrl = serviceUrl;
    }

    @Override
    public void onPreReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
        OutputStream msgOut = rp.getMessageOutputStream();

        // Read the validation record ID stored by PushStorePersistenceHook.validationResultHook
        String validationRecordId = rp.getRepository().getConfig().getString("gitproxy", null, "validationRecordId");
        if (validationRecordId == null) {
            log.warn("No validationRecordId in repo config - skipping approval gate");
            return;
        }

        var record = pushStore.findById(validationRecordId).orElse(null);
        if (record == null) {
            log.warn("Validation record not found: {} - skipping approval gate", validationRecordId);
            return;
        }

        // Safety net: already approved before this hook ran (race condition or re-push)
        if (record.getStatus() == PushStatus.APPROVED) {
            sendAndFlush(rp, msgOut, color(GREEN, "" + sym(HEAVY_CHECK_MARK) + "  Push already approved - forwarding"));
            return;
        }

        // All clean pushes are BLOCKED pending human review
        if (record.getStatus() == PushStatus.BLOCKED) {
            if (approvalGateway.approvesImmediately()) {
                // Auto-approval: approve silently, no waiting messages or dashboard links
                approvalGateway.waitForApproval(validationRecordId, msg -> {}, timeout);
                return;
            }

            sendAndFlush(
                    rp, msgOut, color(YELLOW, "" + sym(WARNING) + "  Push requires review. Waiting for approval..."));
            sendAndFlush(rp, msgOut, color(CYAN, "" + sym(KEY) + "  Push ID: " + validationRecordId));
            if (serviceUrl != null) {
                sendAndFlush(rp, msgOut, color(CYAN, "   Review at: " + serviceUrl + "/#/push/" + validationRecordId));
            }
            if (record.getBlockedMessage() != null) {
                sendAndFlush(rp, msgOut, color(YELLOW, "   Reason: " + record.getBlockedMessage()));
            }

            ApprovalResult result =
                    approvalGateway.waitForApproval(validationRecordId, msg -> sendAndFlush(rp, msgOut, msg), timeout);

            switch (result) {
                case APPROVED ->
                    sendAndFlush(rp, msgOut, color(GREEN, "" + sym(HEAVY_CHECK_MARK) + "  Push approved by reviewer"));
                case REJECTED -> {
                    var updated = pushStore.findById(validationRecordId).orElse(null);
                    String reason = updated != null && updated.getAttestation() != null
                            ? updated.getAttestation().getReason()
                            : "Push rejected by reviewer";
                    sendAndFlush(rp, msgOut, color(RED, "" + sym(CROSS_MARK) + "  Push rejected: " + reason));
                    rejectAll(commands, reason);
                }
                case CANCELED -> {
                    sendAndFlush(rp, msgOut, color(YELLOW, "" + sym(WARNING) + "  Push canceled"));
                    rejectAll(commands, "Push canceled");
                }
                case TIMED_OUT -> {
                    sendAndFlush(
                            rp,
                            msgOut,
                            color(
                                    RED,
                                    "" + sym(CROSS_MARK) + "  Approval timed out after " + timeout.toMinutes()
                                            + " minutes"));
                    rejectAll(commands, "Approval timed out");
                }
            }
        }
    }

    private void rejectAll(Collection<ReceiveCommand> commands, String reason) {
        for (ReceiveCommand cmd : commands) {
            if (cmd.getResult() == ReceiveCommand.Result.NOT_ATTEMPTED) {
                cmd.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, reason);
            }
        }
    }

    private void sendAndFlush(ReceivePack rp, OutputStream msgOut, String message) {
        rp.sendMessage(message);
        try {
            msgOut.flush();
        } catch (IOException e) {
            log.warn("Failed to flush sideband message", e);
        }
    }
}
