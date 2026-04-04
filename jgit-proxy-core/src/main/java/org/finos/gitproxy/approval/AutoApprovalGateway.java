package org.finos.gitproxy.approval;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.model.Attestation;

/**
 * Approval gateway that immediately approves every clean push without waiting for human review. Suitable for standalone
 * deployments where no approval UI is available.
 *
 * <p>This gateway is only ever invoked for pushes that passed all validation checks (status {@code BLOCKED} pending
 * review). Pushes that fail validation are rejected before the approval gate is reached.
 */
@Slf4j
public class AutoApprovalGateway implements ApprovalGateway {

    private final PushStore pushStore;

    public AutoApprovalGateway(PushStore pushStore) {
        this.pushStore = pushStore;
    }

    @Override
    public boolean approvesImmediately() {
        return true;
    }

    @Override
    public ApprovalResult waitForApproval(String pushId, ProgressSender progress, Duration timeout) {
        try {
            pushStore.approve(
                    pushId,
                    Attestation.builder()
                            .pushId(pushId)
                            .type(Attestation.Type.APPROVAL)
                            .reviewerUsername("auto-approval")
                            .reason("Automatically approved — no validation issues found")
                            .automated(true)
                            .build());
            log.info("Auto-approved push: {}", pushId);
        } catch (Exception e) {
            log.warn("Failed to record auto-approval for push {}", pushId, e);
        }
        return ApprovalResult.APPROVED;
    }
}
