package org.finos.gitproxy.approval;

import java.time.Duration;

/**
 * Abstraction for the human approval gate that can block a push pending review. The default implementation polls the
 * push store (UI-based approval). Custom implementations can integrate with external systems (e.g., ServiceNow).
 */
public interface ApprovalGateway {
    /**
     * Wait until the push is approved, rejected, or canceled, or until timeout expires. Implementations should send
     * heartbeat progress messages to keep the git client alive.
     */
    ApprovalResult waitForApproval(String pushId, ProgressSender progress, Duration timeout);
}
