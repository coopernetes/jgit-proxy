package org.finos.gitproxy.approval;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.finos.gitproxy.db.memory.InMemoryPushStore;
import org.finos.gitproxy.db.model.PushRecord;
import org.finos.gitproxy.db.model.PushStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AutoApprovalGatewayTest {

    private InMemoryPushStore pushStore;
    private AutoApprovalGateway gateway;

    @BeforeEach
    void setUp() {
        pushStore = new InMemoryPushStore();
        gateway = new AutoApprovalGateway(pushStore);
    }

    private PushRecord blockedRecord() {
        PushRecord r = PushRecord.builder().build();
        r.setStatus(PushStatus.BLOCKED);
        pushStore.save(r);
        return r;
    }

    @Test
    void returnsApproved_immediately() {
        PushRecord r = blockedRecord();

        ApprovalResult result = gateway.waitForApproval(r.getId(), msg -> {}, Duration.ofSeconds(30));

        assertEquals(ApprovalResult.APPROVED, result);
    }

    @Test
    void recordsApprovalInStore() {
        PushRecord r = blockedRecord();

        gateway.waitForApproval(r.getId(), msg -> {}, Duration.ofSeconds(30));

        PushRecord updated = pushStore.findById(r.getId()).orElseThrow();
        assertEquals(PushStatus.APPROVED, updated.getStatus());
    }

    @Test
    void attestation_isMarkedAutomated() {
        PushRecord r = blockedRecord();

        gateway.waitForApproval(r.getId(), msg -> {}, Duration.ofSeconds(30));

        PushRecord updated = pushStore.findById(r.getId()).orElseThrow();
        assertNotNull(updated.getAttestation(), "Attestation should be set");
        assertTrue(updated.getAttestation().isAutomated(), "Attestation should be automated");
        assertEquals("auto-approval", updated.getAttestation().getReviewerUsername());
    }

    @Test
    void sendsNoProgressMessages() {
        PushRecord r = blockedRecord();
        List<String> messages = new ArrayList<>();

        gateway.waitForApproval(r.getId(), messages::add, Duration.ofSeconds(30));

        assertTrue(messages.isEmpty(), "AutoApprovalGateway should not send any progress messages");
    }

    @Test
    void returnsApproved_evenWhenStoreUpdateFails() {
        // Gateway should still return APPROVED if the store throws (e.g. record not found)
        ApprovalResult result = gateway.waitForApproval("no-such-id", msg -> {}, Duration.ofSeconds(30));

        assertEquals(ApprovalResult.APPROVED, result);
    }
}
