package org.finos.gitproxy.git;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.nullable;
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
import org.finos.gitproxy.db.model.StepStatus;
import org.finos.gitproxy.provider.GitHubProvider;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.service.PushIdentityResolver;
import org.finos.gitproxy.service.UserAuthorizationService;
import org.finos.gitproxy.user.UserEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckUserPushPermissionHookTest {

    @TempDir
    Path tempDir;

    Git git;
    Repository repo;
    PushIdentityResolver resolver;
    UserAuthorizationService authService;

    @BeforeEach
    void setUp() throws Exception {
        git = Git.init().setDirectory(tempDir.toFile()).call();
        repo = git.getRepository();
        repo.getConfig().setBoolean("commit", null, "gpgsign", false);
        repo.getConfig().save();
        resolver = mock(PushIdentityResolver.class);
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

    private CheckUserPushPermissionHook hook(ValidationContext vc, PushContext pc) {
        return new CheckUserPushPermissionHook(resolver, authService, vc, pc);
    }

    private static UserEntry userEntry(String username) {
        return UserEntry.builder()
                .username(username)
                .emails(List.of())
                .scmIdentities(List.of())
                .build();
    }

    // ---- no pushUser in repo config → skip ----

    @Test
    void noPushUser_skipsCheck_recordsPass() throws Exception {
        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pushContext = new PushContext();
        ValidationContext validationContext = new ValidationContext();

        hook(validationContext, pushContext).onPreReceive(rp, List.of(cmd));

        assertFalse(pushContext.getSteps().isEmpty());
        assertEquals(StepStatus.PASS, pushContext.getSteps().get(0).getStatus());
        assertFalse(validationContext.hasIssues());
        verifyNoInteractions(resolver, authService);
    }

    // ---- resolver returns empty → "identity not linked" ----

    @Test
    void resolverReturnsEmpty_addsNotRegisteredIssue() throws Exception {
        repo.getConfig().setString("gitproxy", null, "pushUser", "unknown-user");
        repo.getConfig().save();
        when(resolver.resolve(nullable(GitProxyProvider.class), eq("unknown-user"), any()))
                .thenReturn(Optional.empty());

        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pushContext = new PushContext();
        ValidationContext validationContext = new ValidationContext();

        hook(validationContext, pushContext).onPreReceive(rp, List.of(cmd));

        assertTrue(validationContext.hasIssues());
        assertEquals(
                "CheckUserPushPermissionHook",
                validationContext.getIssues().get(0).hookName());
        assertTrue(
                validationContext.getIssues().get(0).summary().contains("Identity not linked"),
                "Issue message should mention 'Identity not linked'");
        verifyNoInteractions(authService);
    }

    // ---- resolver resolves user but not authorized ----

    @Test
    void userNotAuthorized_addsUnauthorizedIssue() throws Exception {
        repo.getConfig().setString("gitproxy", null, "pushUser", "corp-user");
        repo.getConfig().save();
        when(resolver.resolve(nullable(GitProxyProvider.class), eq("corp-user"), any()))
                .thenReturn(Optional.of(userEntry("alice")));
        when(authService.isUserAuthorizedToPush("alice", null)).thenReturn(false);

        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pushContext = new PushContext();
        ValidationContext validationContext = new ValidationContext();

        hook(validationContext, pushContext).onPreReceive(rp, List.of(cmd));

        assertTrue(validationContext.hasIssues());
        assertEquals(
                "CheckUserPushPermissionHook",
                validationContext.getIssues().get(0).hookName());
        assertTrue(
                validationContext.getIssues().get(0).summary().contains("not authorized"),
                "Issue message should mention 'not authorized'");
    }

    // ---- resolver resolves and authorized → PASS ----

    @Test
    void resolvedAndAuthorized_recordsPass() throws Exception {
        repo.getConfig().setString("gitproxy", null, "pushUser", "corp-user");
        repo.getConfig().setString("gitproxy", null, "pushToken", "ghp_secret");
        repo.getConfig().save();
        when(resolver.resolve(nullable(GitProxyProvider.class), eq("corp-user"), eq("ghp_secret")))
                .thenReturn(Optional.of(userEntry("alice")));
        when(authService.isUserAuthorizedToPush("alice", null)).thenReturn(true);

        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pushContext = new PushContext();
        ValidationContext validationContext = new ValidationContext();

        hook(validationContext, pushContext).onPreReceive(rp, List.of(cmd));

        assertFalse(validationContext.hasIssues());
        assertFalse(pushContext.getSteps().isEmpty());
        assertEquals(StepStatus.PASS, pushContext.getSteps().get(0).getStatus());
    }

    // ---- null resolver (open mode) → always passes, credentials are ignored ----

    @Test
    void nullResolver_withPushUser_passesInOpenMode() throws Exception {
        repo.getConfig().setString("gitproxy", null, "pushUser", "anyone");
        repo.getConfig().save();

        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pushContext = new PushContext();
        ValidationContext validationContext = new ValidationContext();

        new CheckUserPushPermissionHook(null, authService, validationContext, pushContext)
                .onPreReceive(rp, List.of(cmd));

        assertFalse(
                validationContext.hasIssues(), "Null resolver (open mode) should pass — no identity check configured");
        assertFalse(pushContext.getSteps().isEmpty());
        assertEquals(StepStatus.PASS, pushContext.getSteps().get(0).getStatus());
    }

    // ---- provider instance is passed through to resolver ----

    @Test
    void provider_isPassedToResolver() throws Exception {
        repo.getConfig().setString("gitproxy", null, "pushUser", "my-user");
        repo.getConfig().save();
        GitProxyProvider github = new GitHubProvider("/proxy");
        when(resolver.resolve(eq(github), eq("my-user"), any())).thenReturn(Optional.of(userEntry("my-user")));
        when(authService.isUserAuthorizedToPush("my-user", null)).thenReturn(true);

        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pushContext = new PushContext();
        ValidationContext validationContext = new ValidationContext();

        new CheckUserPushPermissionHook(resolver, authService, validationContext, pushContext, github)
                .onPreReceive(rp, List.of(cmd));

        verify(resolver).resolve(eq(github), eq("my-user"), any());
        assertFalse(validationContext.hasIssues());
    }
}
