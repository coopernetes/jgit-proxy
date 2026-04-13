package org.finos.gitproxy.git;

import static org.finos.gitproxy.git.GitClientUtils.AnsiColor.*;
import static org.finos.gitproxy.git.GitClientUtils.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClientUtils.color;
import static org.finos.gitproxy.git.GitClientUtils.sym;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.finos.gitproxy.approval.ApprovalGateway;
import org.finos.gitproxy.approval.ApprovalResult;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.model.Attestation;
import org.finos.gitproxy.db.model.PushStatus;
import org.finos.gitproxy.permission.RepoPermissionService;

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
    private static final ExecutorService APPROVAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final PushStore pushStore;
    private final ApprovalGateway approvalGateway;
    private final Duration timeout;
    private final String serviceUrl;
    private final RepoPermissionService repoPermissionService;

    public ApprovalPreReceiveHook(PushStore pushStore, ApprovalGateway approvalGateway) {
        this(pushStore, approvalGateway, DEFAULT_TIMEOUT, null, null);
    }

    public ApprovalPreReceiveHook(PushStore pushStore, ApprovalGateway approvalGateway, String serviceUrl) {
        this(pushStore, approvalGateway, DEFAULT_TIMEOUT, serviceUrl, null);
    }

    public ApprovalPreReceiveHook(
            PushStore pushStore,
            ApprovalGateway approvalGateway,
            String serviceUrl,
            RepoPermissionService repoPermissionService) {
        this(pushStore, approvalGateway, DEFAULT_TIMEOUT, serviceUrl, repoPermissionService);
    }

    public ApprovalPreReceiveHook(PushStore pushStore, ApprovalGateway approvalGateway, Duration timeout) {
        this(pushStore, approvalGateway, timeout, null, null);
    }

    public ApprovalPreReceiveHook(
            PushStore pushStore, ApprovalGateway approvalGateway, Duration timeout, String serviceUrl) {
        this(pushStore, approvalGateway, timeout, serviceUrl, null);
    }

    public ApprovalPreReceiveHook(
            PushStore pushStore,
            ApprovalGateway approvalGateway,
            Duration timeout,
            String serviceUrl,
            RepoPermissionService repoPermissionService) {
        this.pushStore = pushStore;
        this.approvalGateway = approvalGateway;
        this.timeout = timeout;
        this.serviceUrl = serviceUrl;
        this.repoPermissionService = repoPermissionService;
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
            if (!verifySelfApprovalEntitled(record)) {
                String reason = "Self-approved push rejected: no SELF_CERTIFY permission for this repository";
                sendAndFlush(rp, msgOut, color(RED, "" + sym(CROSS_MARK) + "  " + reason));
                rejectAll(commands, reason);
                return;
            }
            sendAndFlush(rp, msgOut, color(GREEN, "" + sym(HEAVY_CHECK_MARK) + "  Push already approved - forwarding"));
            return;
        }

        // All clean pushes are PENDING human review
        if (record.getStatus() == PushStatus.PENDING) {
            if (approvalGateway.approvesImmediately()) {
                // Auto-approval: approve silently, no waiting messages or dashboard links
                approvalGateway.waitForApproval(validationRecordId, msg -> {}, timeout);
                return;
            }

            sendAndFlush(
                    rp, msgOut, color(YELLOW, "" + sym(WARNING) + "  Push requires review. Waiting for approval..."));
            sendAndFlush(rp, msgOut, color(CYAN, "" + sym(KEY) + "  Push ID: " + validationRecordId));
            if (serviceUrl != null) {
                sendAndFlush(rp, msgOut, color(CYAN, "   Review at: " + serviceUrl + "/push/" + validationRecordId));
            }
            if (record.getBlockedMessage() != null) {
                sendAndFlush(rp, msgOut, color(YELLOW, "   Reason: " + record.getBlockedMessage()));
            }

            Future<ApprovalResult> future = APPROVAL_EXECUTOR.submit(() ->
                    approvalGateway.waitForApproval(validationRecordId, msg -> sendAndFlush(rp, msgOut, msg), timeout));
            ApprovalResult result;
            try {
                result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                result = ApprovalResult.TIMED_OUT;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                result = ApprovalResult.CANCELED;
            } catch (ExecutionException e) {
                log.error("Unexpected error in approval gateway", e.getCause());
                rejectAll(commands, "Approval error");
                return;
            }

            switch (result) {
                case APPROVED -> {
                    var approvedRecord = pushStore.findById(validationRecordId).orElse(null);
                    if (approvedRecord != null && !verifySelfApprovalEntitled(approvedRecord)) {
                        String reason = "Self-approved push rejected: no SELF_CERTIFY permission for this repository";
                        sendAndFlush(rp, msgOut, color(RED, "" + sym(CROSS_MARK) + "  " + reason));
                        rejectAll(commands, reason);
                        return;
                    }
                    sendAndFlush(rp, msgOut, color(GREEN, "" + sym(HEAVY_CHECK_MARK) + "  Push approved by reviewer"));
                }
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

    /**
     * Defense in depth: if the approver is the pusher, re-verify that a {@code SELF_CERTIFY} repo permission still
     * exists for the pusher on this push's path. {@link org.finos.gitproxy.dashboard.controller.PushController#approve}
     * already enforces this at approval time, but re-checking here protects against future code paths or bugs that mark
     * a record APPROVED without going through that gate.
     *
     * <p>The {@code ROLE_SELF_CERTIFY} role check is intentionally NOT performed at the hook layer — it requires Spring
     * Security context (only available in the dashboard) and may live in IdP-derived authorities that aren't persisted
     * to the user store. The hook re-verifies only the per-repo permission, which is the more granular authoritative
     * gate.
     *
     * @return {@code true} if the push may proceed; {@code false} if the approver was the pusher and no
     *     {@code SELF_CERTIFY} permission row exists.
     */
    private boolean verifySelfApprovalEntitled(org.finos.gitproxy.db.model.PushRecord record) {
        if (repoPermissionService == null) return true;
        Attestation att = record.getAttestation();
        if (att == null) return true;
        String pusher = record.getResolvedUser();
        String approver = att.getReviewerUsername();
        if (pusher == null || approver == null || !pusher.equals(approver)) return true;
        if (record.getProvider() == null || record.getUrl() == null) return true;
        boolean entitled = repoPermissionService.isBypassReviewAllowed(pusher, record.getProvider(), record.getUrl());
        if (!entitled) {
            log.warn(
                    "Self-approval rejected at hook: pusher={} provider={} path={} has no SELF_CERTIFY permission",
                    pusher,
                    record.getProvider(),
                    record.getUrl());
        }
        return entitled;
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
