package org.finos.gitproxy.git;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.finos.gitproxy.approval.ApprovalGateway;
import org.finos.gitproxy.approval.ApprovalResult;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.model.Attestation;
import org.finos.gitproxy.db.model.PushRecord;
import org.finos.gitproxy.db.model.PushStatus;
import org.finos.gitproxy.permission.RepoPermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApprovalPreReceiveHookTest {

    @TempDir
    Path tempDir;

    Git git;
    Repository repo;
    PushStore pushStore;
    ApprovalGateway approvalGateway;

    @BeforeEach
    void setUp() throws Exception {
        git = Git.init().setDirectory(tempDir.toFile()).call();
        repo = git.getRepository();
        repo.getConfig().setBoolean("commit", null, "gpgsign", false);
        repo.getConfig().save();
        pushStore = mock(PushStore.class);
        approvalGateway = mock(ApprovalGateway.class);
    }

    private RevCommit createCommit(String msg) throws Exception {
        File f = new File(tempDir.toFile(), UUID.randomUUID() + ".txt");
        Files.writeString(f.toPath(), msg);
        git.add().addFilepattern(".").call();
        return git.commit()
                .setAuthor(new PersonIdent("Dev", "dev@example.com"))
                .setCommitter(new PersonIdent("Dev", "dev@example.com"))
                .setMessage(msg)
                .call();
    }

    @Test
    void noValidationRecordId_skipsApprovalGate() throws Exception {
        // No gitproxy.validationRecordId set in repo config → hook is a no-op
        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);

        new ApprovalPreReceiveHook(pushStore, approvalGateway).onPreReceive(rp, List.of(cmd));

        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, cmd.getResult());
        verifyNoInteractions(pushStore, approvalGateway);
    }

    @Test
    void validationRecordNotInStore_skipsApprovalGate() throws Exception {
        String recordId = UUID.randomUUID().toString();
        repo.getConfig().setString("gitproxy", null, "validationRecordId", recordId);
        repo.getConfig().save();
        when(pushStore.findById(recordId)).thenReturn(Optional.empty());

        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);

        new ApprovalPreReceiveHook(pushStore, approvalGateway).onPreReceive(rp, List.of(cmd));

        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, cmd.getResult());
        verifyNoInteractions(approvalGateway);
    }

    @Test
    void alreadyApproved_passesImmediately() throws Exception {
        String recordId = UUID.randomUUID().toString();
        repo.getConfig().setString("gitproxy", null, "validationRecordId", recordId);
        repo.getConfig().save();
        PushRecord record =
                PushRecord.builder().id(recordId).status(PushStatus.APPROVED).build();
        when(pushStore.findById(recordId)).thenReturn(Optional.of(record));

        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);

        new ApprovalPreReceiveHook(pushStore, approvalGateway).onPreReceive(rp, List.of(cmd));

        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, cmd.getResult());
        verifyNoInteractions(approvalGateway);
    }

    @Test
    void blockedPush_gatewayApproves_commandNotRejected() throws Exception {
        String recordId = UUID.randomUUID().toString();
        repo.getConfig().setString("gitproxy", null, "validationRecordId", recordId);
        repo.getConfig().save();
        PushRecord record =
                PushRecord.builder().id(recordId).status(PushStatus.PENDING).build();
        when(pushStore.findById(recordId)).thenReturn(Optional.of(record));
        when(approvalGateway.waitForApproval(eq(recordId), any(), any(Duration.class)))
                .thenReturn(ApprovalResult.APPROVED);

        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);

        new ApprovalPreReceiveHook(pushStore, approvalGateway, Duration.ofSeconds(5)).onPreReceive(rp, List.of(cmd));

        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, cmd.getResult());
    }

    @Test
    void blockedPush_gatewayRejects_commandRejected() throws Exception {
        String recordId = UUID.randomUUID().toString();
        repo.getConfig().setString("gitproxy", null, "validationRecordId", recordId);
        repo.getConfig().save();
        PushRecord record =
                PushRecord.builder().id(recordId).status(PushStatus.PENDING).build();
        PushRecord updatedRecord =
                PushRecord.builder().id(recordId).status(PushStatus.REJECTED).build();
        when(pushStore.findById(recordId)).thenReturn(Optional.of(record)).thenReturn(Optional.of(updatedRecord));
        when(approvalGateway.waitForApproval(eq(recordId), any(), any(Duration.class)))
                .thenReturn(ApprovalResult.REJECTED);

        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);

        new ApprovalPreReceiveHook(pushStore, approvalGateway, Duration.ofSeconds(5)).onPreReceive(rp, List.of(cmd));

        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, cmd.getResult());
    }

    // ── Defense-in-depth: hook re-verifies SELF_CERTIFY perm when approver == pusher ─────────────

    @Test
    void selfApproved_alreadyApprovedAtHookStart_noPerm_rejected() throws Exception {
        String recordId = UUID.randomUUID().toString();
        repo.getConfig().setString("gitproxy", null, "validationRecordId", recordId);
        repo.getConfig().save();
        Attestation att = Attestation.builder()
                .pushId(recordId)
                .type(Attestation.Type.APPROVAL)
                .reviewerUsername("alice")
                .build();
        PushRecord record = PushRecord.builder()
                .id(recordId)
                .status(PushStatus.APPROVED)
                .resolvedUser("alice")
                .provider("github/github.com")
                .url("/owner/repo")
                .attestation(att)
                .build();
        when(pushStore.findById(recordId)).thenReturn(Optional.of(record));
        RepoPermissionService perms = mock(RepoPermissionService.class);
        when(perms.isBypassReviewAllowed("alice", "github/github.com", "/owner/repo"))
                .thenReturn(false);

        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);

        new ApprovalPreReceiveHook(pushStore, approvalGateway, Duration.ofSeconds(5), null, perms)
                .onPreReceive(rp, List.of(cmd));

        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, cmd.getResult());
        verify(perms).isBypassReviewAllowed("alice", "github/github.com", "/owner/repo");
    }

    @Test
    void selfApproved_alreadyApprovedAtHookStart_withPerm_passes() throws Exception {
        String recordId = UUID.randomUUID().toString();
        repo.getConfig().setString("gitproxy", null, "validationRecordId", recordId);
        repo.getConfig().save();
        Attestation att = Attestation.builder()
                .pushId(recordId)
                .type(Attestation.Type.APPROVAL)
                .reviewerUsername("alice")
                .build();
        PushRecord record = PushRecord.builder()
                .id(recordId)
                .status(PushStatus.APPROVED)
                .resolvedUser("alice")
                .provider("github/github.com")
                .url("/owner/repo")
                .attestation(att)
                .build();
        when(pushStore.findById(recordId)).thenReturn(Optional.of(record));
        RepoPermissionService perms = mock(RepoPermissionService.class);
        when(perms.isBypassReviewAllowed("alice", "github/github.com", "/owner/repo"))
                .thenReturn(true);

        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);

        new ApprovalPreReceiveHook(pushStore, approvalGateway, Duration.ofSeconds(5), null, perms)
                .onPreReceive(rp, List.of(cmd));

        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, cmd.getResult());
    }

    @Test
    void selfApproved_viaWaitForApproval_noPerm_rejected() throws Exception {
        String recordId = UUID.randomUUID().toString();
        repo.getConfig().setString("gitproxy", null, "validationRecordId", recordId);
        repo.getConfig().save();
        // Initial fetch returns PENDING (no attestation yet); after approval, returns APPROVED with attestation
        // showing the pusher self-approved.
        PushRecord pending = PushRecord.builder()
                .id(recordId)
                .status(PushStatus.PENDING)
                .resolvedUser("alice")
                .provider("github/github.com")
                .url("/owner/repo")
                .build();
        Attestation att = Attestation.builder()
                .pushId(recordId)
                .type(Attestation.Type.APPROVAL)
                .reviewerUsername("alice")
                .build();
        PushRecord approved = PushRecord.builder()
                .id(recordId)
                .status(PushStatus.APPROVED)
                .resolvedUser("alice")
                .provider("github/github.com")
                .url("/owner/repo")
                .attestation(att)
                .build();
        when(pushStore.findById(recordId)).thenReturn(Optional.of(pending)).thenReturn(Optional.of(approved));
        when(approvalGateway.waitForApproval(eq(recordId), any(), any(Duration.class)))
                .thenReturn(ApprovalResult.APPROVED);
        RepoPermissionService perms = mock(RepoPermissionService.class);
        when(perms.isBypassReviewAllowed("alice", "github/github.com", "/owner/repo"))
                .thenReturn(false);

        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);

        new ApprovalPreReceiveHook(pushStore, approvalGateway, Duration.ofSeconds(5), null, perms)
                .onPreReceive(rp, List.of(cmd));

        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, cmd.getResult());
        verify(perms).isBypassReviewAllowed("alice", "github/github.com", "/owner/repo");
    }

    @Test
    void differentApproverThanPusher_noReVerifyNeeded() throws Exception {
        // Approver != pusher → defense-in-depth check skipped; push is forwarded.
        String recordId = UUID.randomUUID().toString();
        repo.getConfig().setString("gitproxy", null, "validationRecordId", recordId);
        repo.getConfig().save();
        Attestation att = Attestation.builder()
                .pushId(recordId)
                .type(Attestation.Type.APPROVAL)
                .reviewerUsername("bob")
                .build();
        PushRecord record = PushRecord.builder()
                .id(recordId)
                .status(PushStatus.APPROVED)
                .resolvedUser("alice")
                .provider("github/github.com")
                .url("/owner/repo")
                .attestation(att)
                .build();
        when(pushStore.findById(recordId)).thenReturn(Optional.of(record));
        RepoPermissionService perms = mock(RepoPermissionService.class);

        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);

        new ApprovalPreReceiveHook(pushStore, approvalGateway, Duration.ofSeconds(5), null, perms)
                .onPreReceive(rp, List.of(cmd));

        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, cmd.getResult());
        verifyNoInteractions(perms);
    }

    @Test
    void blockedPush_gatewayTimesOut_commandRejected() throws Exception {
        String recordId = UUID.randomUUID().toString();
        repo.getConfig().setString("gitproxy", null, "validationRecordId", recordId);
        repo.getConfig().save();
        PushRecord record =
                PushRecord.builder().id(recordId).status(PushStatus.PENDING).build();
        when(pushStore.findById(recordId)).thenReturn(Optional.of(record));
        when(approvalGateway.waitForApproval(eq(recordId), any(), any(Duration.class)))
                .thenReturn(ApprovalResult.TIMED_OUT);

        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);

        new ApprovalPreReceiveHook(pushStore, approvalGateway, Duration.ofSeconds(5)).onPreReceive(rp, List.of(cmd));

        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, cmd.getResult());
    }
}
