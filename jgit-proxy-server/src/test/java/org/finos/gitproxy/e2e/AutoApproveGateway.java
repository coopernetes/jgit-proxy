package org.finos.gitproxy.e2e;

import java.time.Duration;
import org.finos.gitproxy.approval.ApprovalGateway;
import org.finos.gitproxy.approval.ApprovalResult;
import org.finos.gitproxy.approval.ProgressSender;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.model.Attestation;

/**
 * Test-only {@link ApprovalGateway} that immediately approves every push without waiting for human input. Used in e2e
 * tests so that valid commits are forwarded without blocking.
 */
class AutoApproveGateway implements ApprovalGateway {

    private final PushStore pushStore;

    AutoApproveGateway(PushStore pushStore) {
        this.pushStore = pushStore;
    }

    @Override
    public ApprovalResult waitForApproval(String pushId, ProgressSender progress, Duration timeout) {
        pushStore.approve(
                pushId,
                Attestation.builder()
                        .pushId(pushId)
                        .type(Attestation.Type.APPROVAL)
                        .reviewerUsername("e2e-auto-approver")
                        .reason("Automatically approved by e2e test fixture")
                        .automated(true)
                        .build());
        return ApprovalResult.APPROVED;
    }
}
