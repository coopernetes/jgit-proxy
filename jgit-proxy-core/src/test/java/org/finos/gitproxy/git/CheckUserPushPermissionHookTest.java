package org.finos.gitproxy.git;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.finos.gitproxy.db.model.StepStatus;
import org.finos.gitproxy.service.UserAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckUserPushPermissionHookTest {

    @TempDir
    Path tempDir;

    Git git;
    Repository repo;
    UserAuthorizationService authService;

    @BeforeEach
    void setUp() throws Exception {
        git = Git.init().setDirectory(tempDir.toFile()).call();
        repo = git.getRepository();
        repo.getConfig().setBoolean("commit", null, "gpgsign", false);
        repo.getConfig().save();
        authService = mock(UserAuthorizationService.class);
    }

    private RevCommit createCommit(String message) throws Exception {
        File f = new File(tempDir.toFile(), UUID.randomUUID() + ".txt");
        Files.writeString(f.toPath(), message);
        git.add().addFilepattern(".").call();
        return git.commit()
                .setAuthor(new PersonIdent("Dev", "dev@example.com"))
                .setCommitter(new PersonIdent("Dev", "dev@example.com"))
                .setMessage(message)
                .call();
    }

    @Test
    void noPushUser_skipsCheck_recordsPass() throws Exception {
        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pushContext = new PushContext();
        ValidationContext validationContext = new ValidationContext();

        new CheckUserPushPermissionHook(authService, validationContext, pushContext).onPreReceive(rp, List.of(cmd));

        assertFalse(pushContext.getSteps().isEmpty());
        assertEquals(StepStatus.PASS, pushContext.getSteps().get(0).getStatus());
        assertFalse(validationContext.hasIssues());
        verifyNoInteractions(authService);
    }

    @Test
    void userExistsAndAuthorized_recordsPass() throws Exception {
        repo.getConfig().setString("gitproxy", null, "pushUser", "alice@example.com");
        repo.getConfig().save();
        when(authService.userExists("alice@example.com")).thenReturn(true);
        when(authService.isUserAuthorizedToPush("alice@example.com", null)).thenReturn(true);

        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pushContext = new PushContext();
        ValidationContext validationContext = new ValidationContext();

        new CheckUserPushPermissionHook(authService, validationContext, pushContext).onPreReceive(rp, List.of(cmd));

        assertFalse(pushContext.getSteps().isEmpty());
        assertEquals(StepStatus.PASS, pushContext.getSteps().get(0).getStatus());
        assertFalse(validationContext.hasIssues());
    }

    @Test
    void userDoesNotExist_addsValidationIssue() throws Exception {
        repo.getConfig().setString("gitproxy", null, "pushUser", "ghost@example.com");
        repo.getConfig().save();
        when(authService.userExists("ghost@example.com")).thenReturn(false);

        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pushContext = new PushContext();
        ValidationContext validationContext = new ValidationContext();

        new CheckUserPushPermissionHook(authService, validationContext, pushContext).onPreReceive(rp, List.of(cmd));

        assertTrue(validationContext.hasIssues());
        assertEquals(
                "CheckUserPushPermissionHook",
                validationContext.getIssues().get(0).hookName());
    }

    @Test
    void userNotAuthorized_addsValidationIssue() throws Exception {
        repo.getConfig().setString("gitproxy", null, "pushUser", "blocked@example.com");
        repo.getConfig().save();
        when(authService.userExists("blocked@example.com")).thenReturn(true);
        when(authService.isUserAuthorizedToPush("blocked@example.com", null)).thenReturn(false);

        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pushContext = new PushContext();
        ValidationContext validationContext = new ValidationContext();

        new CheckUserPushPermissionHook(authService, validationContext, pushContext).onPreReceive(rp, List.of(cmd));

        assertTrue(validationContext.hasIssues());
        assertEquals(
                "CheckUserPushPermissionHook",
                validationContext.getIssues().get(0).hookName());
    }
}
