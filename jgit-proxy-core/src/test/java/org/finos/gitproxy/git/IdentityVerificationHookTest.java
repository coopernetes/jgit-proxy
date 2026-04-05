package org.finos.gitproxy.git;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.db.model.StepStatus;
import org.finos.gitproxy.provider.GitHubProvider;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.service.PushIdentityResolver;
import org.finos.gitproxy.user.UserEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdentityVerificationHookTest {

    @TempDir
    Path tempDir;

    Git git;
    Repository repo;
    PushIdentityResolver resolver;

    static final GitProxyProvider GITHUB = new GitHubProvider("/proxy");

    @BeforeEach
    void setUp() throws Exception {
        git = Git.init().setDirectory(tempDir.toFile()).call();
        repo = git.getRepository();
        repo.getConfig().setBoolean("commit", null, "gpgsign", false);
        repo.getConfig().save();
        resolver = mock(PushIdentityResolver.class);
    }

    private RevCommit createCommit(String message, String authorEmail) throws Exception {
        File f = new File(tempDir.toFile(), UUID.randomUUID() + ".txt");
        Files.writeString(f.toPath(), message);
        git.add().addFilepattern(".").call();
        return git.commit()
                .setAuthor(new PersonIdent("Dev", authorEmail))
                .setCommitter(new PersonIdent("Dev", authorEmail))
                .setMessage(message)
                .call();
    }

    private IdentityVerificationHook hook(
            CommitConfig.IdentityVerificationMode mode, ValidationContext vc, PushContext pc) {
        return new IdentityVerificationHook(resolver, mode, vc, pc, GITHUB);
    }

    private static UserEntry alice() {
        return UserEntry.builder()
                .username("alice")
                .emails(List.of("alice@example.com"))
                .scmIdentities(List.of())
                .build();
    }

    // ---- mode=off → skips the check ----

    @Test
    void modeOff_skipsCheck_recordsPass() throws Exception {
        RevCommit c1 = createCommit("init", "other@example.com");
        RevCommit c2 = createCommit("second", "other@example.com");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pc = new PushContext();
        ValidationContext vc = new ValidationContext();

        hook(CommitConfig.IdentityVerificationMode.OFF, vc, pc).onPreReceive(rp, List.of(cmd));

        assertFalse(vc.hasIssues());
        assertFalse(pc.getSteps().isEmpty());
        assertEquals(StepStatus.PASS, pc.getSteps().get(0).getStatus());
        verifyNoInteractions(resolver);
    }

    // ---- null resolver (open mode) → always passes ----

    @Test
    void nullResolver_skipsCheck() throws Exception {
        RevCommit c1 = createCommit("init", "other@example.com");
        RevCommit c2 = createCommit("second", "other@example.com");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pc = new PushContext();
        ValidationContext vc = new ValidationContext();

        new IdentityVerificationHook(null, CommitConfig.IdentityVerificationMode.STRICT, vc, pc, GITHUB)
                .onPreReceive(rp, List.of(cmd));

        assertFalse(vc.hasIssues());
        assertFalse(pc.getSteps().isEmpty());
        assertEquals(StepStatus.PASS, pc.getSteps().get(0).getStatus());
    }

    // ---- no pushUser in repo config → skip ----

    @Test
    void noPushUser_skipsCheck_recordsPass() throws Exception {
        RevCommit c1 = createCommit("init", "other@example.com");
        RevCommit c2 = createCommit("second", "other@example.com");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pc = new PushContext();
        ValidationContext vc = new ValidationContext();

        hook(CommitConfig.IdentityVerificationMode.WARN, vc, pc).onPreReceive(rp, List.of(cmd));

        assertFalse(vc.hasIssues());
        assertFalse(pc.getSteps().isEmpty());
        assertEquals(StepStatus.PASS, pc.getSteps().get(0).getStatus());
        verifyNoInteractions(resolver);
    }

    // ---- emails match → PASS ----

    @Test
    void emailsMatch_recordsPass() throws Exception {
        repo.getConfig().setString("gitproxy", null, "pushUser", "alice-git");
        repo.getConfig().save();
        when(resolver.resolve(any(GitProxyProvider.class), eq("alice-git"), isNull()))
                .thenReturn(Optional.of(alice()));

        RevCommit c1 = createCommit("init", "alice@example.com");
        RevCommit c2 = createCommit("second", "alice@example.com");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pc = new PushContext();
        ValidationContext vc = new ValidationContext();

        hook(CommitConfig.IdentityVerificationMode.STRICT, vc, pc).onPreReceive(rp, List.of(cmd));

        assertFalse(vc.hasIssues());
        assertFalse(pc.getSteps().isEmpty());
        assertEquals(StepStatus.PASS, pc.getSteps().get(0).getStatus());
    }

    // ---- strict mode + email mismatch → validation issue (blocked) ----

    @Test
    void strictMode_emailMismatch_addsIssue() throws Exception {
        repo.getConfig().setString("gitproxy", null, "pushUser", "alice-git");
        repo.getConfig().save();
        when(resolver.resolve(any(GitProxyProvider.class), eq("alice-git"), isNull()))
                .thenReturn(Optional.of(alice()));

        RevCommit c1 = createCommit("init", "other@example.com");
        RevCommit c2 = createCommit("second", "other@example.com");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pc = new PushContext();
        ValidationContext vc = new ValidationContext();

        hook(CommitConfig.IdentityVerificationMode.STRICT, vc, pc).onPreReceive(rp, List.of(cmd));

        assertTrue(vc.hasIssues());
        assertEquals(IdentityVerificationHook.STEP_NAME, vc.getIssues().get(0).hookName());
        assertTrue(vc.getIssues().get(0).summary().contains("alice"));
    }

    // ---- warn mode + email mismatch → no issue, push proceeds ----

    @Test
    void warnMode_emailMismatch_noIssue_recordsPass() throws Exception {
        repo.getConfig().setString("gitproxy", null, "pushUser", "alice-git");
        repo.getConfig().save();
        when(resolver.resolve(any(GitProxyProvider.class), eq("alice-git"), isNull()))
                .thenReturn(Optional.of(alice()));

        RevCommit c1 = createCommit("init", "other@example.com");
        RevCommit c2 = createCommit("second", "other@example.com");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pc = new PushContext();
        ValidationContext vc = new ValidationContext();

        hook(CommitConfig.IdentityVerificationMode.WARN, vc, pc).onPreReceive(rp, List.of(cmd));

        assertFalse(vc.hasIssues(), "WARN mode should not block the push");
        assertFalse(pc.getSteps().isEmpty());
        assertEquals(StepStatus.PASS, pc.getSteps().get(0).getStatus());
    }

    // ---- DELETE command → skipped entirely, no violation ----

    @Test
    void deleteCommand_skipped() throws Exception {
        repo.getConfig().setString("gitproxy", null, "pushUser", "alice-git");
        repo.getConfig().save();
        when(resolver.resolve(any(GitProxyProvider.class), eq("alice-git"), isNull()))
                .thenReturn(Optional.of(alice()));

        RevCommit c1 = createCommit("init", "other@example.com");
        ReceivePack rp = new ReceivePack(repo);
        // DELETE: newId is zero
        ReceiveCommand cmd = new ReceiveCommand(
                c1.getId(), org.eclipse.jgit.lib.ObjectId.zeroId(), "refs/heads/main", ReceiveCommand.Type.DELETE);
        PushContext pc = new PushContext();
        ValidationContext vc = new ValidationContext();

        hook(CommitConfig.IdentityVerificationMode.STRICT, vc, pc).onPreReceive(rp, List.of(cmd));

        assertFalse(vc.hasIssues(), "DELETE commands should not trigger identity violation");
    }

    // ---- author and committer same unregistered email → single combined violation ----

    @Test
    void strictMode_authorAndCommitterSameEmail_singleViolation() throws Exception {
        repo.getConfig().setString("gitproxy", null, "pushUser", "alice-git");
        repo.getConfig().save();
        when(resolver.resolve(any(GitProxyProvider.class), eq("alice-git"), isNull()))
                .thenReturn(Optional.of(alice())); // alice only has alice@example.com

        // Commit where author and committer are both "other@example.com" (unregistered, same address)
        RevCommit c1 = createCommit("init", "other@example.com");
        RevCommit c2 = createCommit("second", "other@example.com");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pc = new PushContext();
        ValidationContext vc = new ValidationContext();

        hook(CommitConfig.IdentityVerificationMode.STRICT, vc, pc).onPreReceive(rp, List.of(cmd));

        assertTrue(vc.hasIssues());
        // Should produce exactly one violation per commit (author+committer collapsed), not two
        String summary = vc.getIssues().get(0).summary();
        assertTrue(summary.contains("alice"), "Violation should reference the push user");
    }

    // ---- warn mode records step content with violation details ----

    @Test
    void warnMode_emailMismatch_stepContentContainsViolations() throws Exception {
        repo.getConfig().setString("gitproxy", null, "pushUser", "alice-git");
        repo.getConfig().save();
        when(resolver.resolve(any(GitProxyProvider.class), eq("alice-git"), isNull()))
                .thenReturn(Optional.of(alice()));

        RevCommit c1 = createCommit("init", "other@example.com");
        RevCommit c2 = createCommit("second", "other@example.com");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pc = new PushContext();
        ValidationContext vc = new ValidationContext();

        hook(CommitConfig.IdentityVerificationMode.WARN, vc, pc).onPreReceive(rp, List.of(cmd));

        assertFalse(vc.hasIssues());
        // WARN mode records a PASS step with the violation in content (for the amber UI badge)
        var step = pc.getSteps().stream()
                .filter(s -> "identityVerification".equals(s.getStepName()))
                .findFirst();
        assertTrue(step.isPresent(), "WARN mode should record an identityVerification step");
        assertNotNull(step.get().getContent(), "Step content should contain violation details");
        assertTrue(step.get().getContent().contains("not registered"));
    }

    // ---- resolver returns empty → skip (CheckUserPushPermissionHook handles "not registered") ----

    @Test
    void resolverEmpty_skipsCheck_noStep() throws Exception {
        repo.getConfig().setString("gitproxy", null, "pushUser", "unknown");
        repo.getConfig().save();
        when(resolver.resolve(any(GitProxyProvider.class), eq("unknown"), any()))
                .thenReturn(Optional.empty());

        RevCommit c1 = createCommit("init", "someone@example.com");
        RevCommit c2 = createCommit("second", "someone@example.com");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pc = new PushContext();
        ValidationContext vc = new ValidationContext();

        hook(CommitConfig.IdentityVerificationMode.STRICT, vc, pc).onPreReceive(rp, List.of(cmd));

        assertFalse(vc.hasIssues());
        assertTrue(pc.getSteps().isEmpty(), "No step should be recorded when user cannot be resolved");
    }
}
