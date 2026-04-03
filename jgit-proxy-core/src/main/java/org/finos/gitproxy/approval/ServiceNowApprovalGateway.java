package org.finos.gitproxy.approval;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

/**
 * Stub approval gateway for ServiceNow integration. In a full implementation this would:
 *
 * <ol>
 *   <li>Create a ServiceNow change request ticket for the push
 *   <li>Poll the ticket's approval state until approved/rejected
 *   <li>Map the ticket outcome to ApprovalResult
 * </ol>
 *
 * <p>Currently returns APPROVED immediately as a placeholder.
 */
@Slf4j
public class ServiceNowApprovalGateway implements ApprovalGateway {

    private final String serviceNowBaseUrl;
    private final String serviceNowCredentials;

    public ServiceNowApprovalGateway(String serviceNowBaseUrl, String serviceNowCredentials) {
        this.serviceNowBaseUrl = serviceNowBaseUrl;
        this.serviceNowCredentials = serviceNowCredentials;
    }

    @Override
    public ApprovalResult waitForApproval(String pushId, ProgressSender progress, Duration timeout) {
        // TODO: Create a ServiceNow change request ticket and poll for approval
        // String ticketId = createChangeRequest(pushId);
        // progress.send("ServiceNow ticket created: " + ticketId);
        // return pollTicketApproval(ticketId, progress, timeout);
        log.info("ServiceNow approval stub: would create ticket for push {}, returning APPROVED", pushId);
        progress.send("ServiceNow approval stub: auto-approving push " + pushId);
        return ApprovalResult.APPROVED;
    }
}
