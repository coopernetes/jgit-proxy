package com.rbc.fogwall.git;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

import com.rbc.fogwall.approval.ClientLivenessCheck;
import com.rbc.fogwall.config.ScmOAuthConfig;
import com.rbc.fogwall.db.model.StepStatus;
import com.rbc.fogwall.permission.RepoPermissionService;
import com.rbc.fogwall.provider.BitbucketProvider;
import com.rbc.fogwall.provider.FogwallProvider;
import com.rbc.fogwall.provider.GitHubProvider;
import com.rbc.fogwall.service.ImportedKeyIdentityResolver;
import com.rbc.fogwall.service.PushIdentityResolver;
import com.rbc.fogwall.user.ScmIdentity;
import com.rbc.fogwall.user.SshKeyEntry;
import com.rbc.fogwall.user.UserEntry;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckUserPushPermissionHookTest {

    @TempDir
    Path tempDir;

    Git git;
    Repository repo;
    PushIdentityResolver resolver;
    RepoPermissionService permService;

    @BeforeEach
    void setUp() throws Exception {
        git = Git.init().setDirectory(tempDir.toFile()).call();
        repo = git.getRepository();
        repo.getConfig().setBoolean("commit", null, "gpgsign", false);
        repo.getConfig().save();
        resolver = mock(PushIdentityResolver.class);
        permService = mock(RepoPermissionService.class);
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
        return new CheckUserPushPermissionHook(resolver, permService, vc, pc);
    }

    private static UserEntry userEntry(String username) {
        return UserEntry.builder()
                .username(username)
                .emails(List.of())
                .scmIdentities(List.of())
                .build();
    }

    // ---- no pushUser in repo config → fail-closed ----

    @Test
    void noPushUser_failsClosed() throws Exception {
        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pushContext = new PushContext();
        ValidationContext validationContext = new ValidationContext();

        hook(validationContext, pushContext).onPreReceive(rp, List.of(cmd));

        assertTrue(validationContext.hasIssues(), "Missing pushUser must produce a validation issue");
        assertEquals(
                "CheckUserPushPermissionHook",
                validationContext.getIssues().get(0).hookName());
        assertTrue(pushContext.getSteps().isEmpty(), "No PASS step should be recorded when check fails");
        verifyNoInteractions(resolver, permService);
    }

    // ---- resolver returns empty → "identity not linked" ----

    @Test
    void resolverReturnsEmpty_addsNotRegisteredIssue() throws Exception {
        when(resolver.resolve(nullable(FogwallProvider.class), eq("unknown-user"), any()))
                .thenReturn(Optional.empty());

        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pushContext = new PushContext();
        pushContext.setPushUser("unknown-user");
        ValidationContext validationContext = new ValidationContext();

        hook(validationContext, pushContext).onPreReceive(rp, List.of(cmd));

        assertTrue(validationContext.hasIssues());
        assertEquals(
                "CheckUserPushPermissionHook",
                validationContext.getIssues().get(0).hookName());
        assertTrue(
                validationContext.getIssues().get(0).summary().contains("Identity not linked"),
                "Issue message should mention 'Identity not linked'");
        verifyNoInteractions(permService);
    }

    // ---- resolver resolves user but not authorized (provider + slug configured) ----

    @Test
    void userNotAuthorized_addsUnauthorizedIssue() throws Exception {
        FogwallProvider github = new GitHubProvider("/push");
        when(resolver.resolve(eq(github), eq("corp-user"), any())).thenReturn(Optional.of(userEntry("alice")));
        when(permService.isAllowedToPush("alice", "github", "/owner/repo")).thenReturn(false);

        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pushContext = new PushContext();
        pushContext.setPushUser("corp-user");
        pushContext.setRepoSlug("/owner/repo");
        ValidationContext validationContext = new ValidationContext();

        new CheckUserPushPermissionHook(resolver, permService, validationContext, pushContext, github, null)
                .onPreReceive(rp, List.of(cmd));

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
        FogwallProvider github = new GitHubProvider("/push");
        when(resolver.resolve(eq(github), eq("corp-user"), eq("ghp_secret")))
                .thenReturn(Optional.of(userEntry("alice")));
        when(permService.isAllowedToPush("alice", "github", "/owner/repo")).thenReturn(true);

        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pushContext = new PushContext();
        pushContext.setPushUser("corp-user");
        pushContext.setPushToken("ghp_secret");
        pushContext.setRepoSlug("/owner/repo");
        ValidationContext validationContext = new ValidationContext();

        new CheckUserPushPermissionHook(resolver, permService, validationContext, pushContext, github, null)
                .onPreReceive(rp, List.of(cmd));

        assertFalse(validationContext.hasIssues());
        assertFalse(pushContext.getSteps().isEmpty());
        assertEquals(StepStatus.PASS, pushContext.getSteps().get(0).getStatus());
    }

    // ---- null resolver (open mode) → always passes, credentials are ignored ----

    @Test
    void nullResolver_withPushUser_passesInOpenMode() throws Exception {
        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pushContext = new PushContext();
        ValidationContext validationContext = new ValidationContext();

        new CheckUserPushPermissionHook(null, permService, validationContext, pushContext)
                .onPreReceive(rp, List.of(cmd));

        assertFalse(
                validationContext.hasIssues(), "Null resolver (open mode) should pass — no identity check configured");
        assertFalse(pushContext.getSteps().isEmpty());
        assertEquals(StepStatus.PASS, pushContext.getSteps().get(0).getStatus());
    }

    // ---- provider instance is passed through to resolver ----

    @Test
    void provider_isPassedToResolver() throws Exception {
        FogwallProvider github = new GitHubProvider("/push");
        when(resolver.resolve(eq(github), eq("my-user"), any())).thenReturn(Optional.of(userEntry("my-user")));
        when(permService.isAllowedToPush("my-user", "github", "/owner/repo")).thenReturn(true);

        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pushContext = new PushContext();
        pushContext.setPushUser("my-user");
        pushContext.setRepoSlug("/owner/repo");
        ValidationContext validationContext = new ValidationContext();

        new CheckUserPushPermissionHook(resolver, permService, validationContext, pushContext, github, null)
                .onPreReceive(rp, List.of(cmd));

        verify(resolver).resolve(eq(github), eq("my-user"), any());
        assertFalse(validationContext.hasIssues());
    }

    // ---- strict identity mode (#40) — HTTP path ----

    /** A user whose connecting key was imported by {@code keyAuthSource}'s OAuth linking, or hand-added when null. */
    private static UserEntry userEntryWithSshKey(
            String username, String provider, String scmUsername, boolean verified, String keyAuthSource) {
        SshKeyEntry key = keyAuthSource != null
                ? SshKeyEntry.builder()
                        .username(username)
                        .fingerprint("SHA256:fingerprint")
                        .locked(true)
                        .authSource(keyAuthSource)
                        .build()
                : SshKeyEntry.builder()
                        .username(username)
                        .fingerprint("SHA256:fingerprint")
                        .locked(false)
                        .authSource("config")
                        .build();
        return UserEntry.builder()
                .username(username)
                .emails(List.of())
                .sshKeys(List.of(key))
                .scmIdentities(List.of(ScmIdentity.builder()
                        .provider(provider)
                        .username(scmUsername)
                        .verified(verified)
                        .build()))
                .build();
    }

    private static UserEntry userEntryWithScmIdentity(
            String username, String provider, String scmUsername, boolean verified) {
        return UserEntry.builder()
                .username(username)
                .emails(List.of())
                .scmIdentities(List.of(ScmIdentity.builder()
                        .provider(provider)
                        .username(scmUsername)
                        .verified(verified)
                        .build()))
                .build();
    }

    @Test
    void strictMode_httpUnverifiedIdentity_blocksPush() throws Exception {
        FogwallProvider github = new GitHubProvider("/push");
        when(resolver.resolve(eq(github), eq("corp-user"), any()))
                .thenReturn(Optional.of(userEntryWithScmIdentity("alice", "github", "alice-gh", false)));
        when(permService.isAllowedToPush("alice", "github", "/owner/repo")).thenReturn(true);

        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pushContext = new PushContext();
        pushContext.setPushUser("corp-user");
        pushContext.setRepoSlug("/owner/repo");
        ValidationContext validationContext = new ValidationContext();

        new CheckUserPushPermissionHook(
                        resolver,
                        permService,
                        validationContext,
                        pushContext,
                        github,
                        null,
                        null,
                        ScmOAuthConfig.IdentityMode.STRICT)
                .onPreReceive(rp, List.of(cmd));

        assertTrue(validationContext.hasIssues());
        assertTrue(
                validationContext.getIssues().get(0).summary().contains("No OAuth-verified SCM identity"),
                "Issue message should mention no verified identity");
    }

    @Test
    void strictMode_httpVerifiedIdentity_allowsPush() throws Exception {
        FogwallProvider github = new GitHubProvider("/push");
        when(resolver.resolve(eq(github), eq("corp-user"), any()))
                .thenReturn(Optional.of(userEntryWithScmIdentity("alice", "github", "alice-gh", true)));
        when(permService.isAllowedToPush("alice", "github", "/owner/repo")).thenReturn(true);

        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pushContext = new PushContext();
        pushContext.setPushUser("corp-user");
        pushContext.setRepoSlug("/owner/repo");
        ValidationContext validationContext = new ValidationContext();

        new CheckUserPushPermissionHook(
                        resolver,
                        permService,
                        validationContext,
                        pushContext,
                        github,
                        null,
                        null,
                        ScmOAuthConfig.IdentityMode.STRICT)
                .onPreReceive(rp, List.of(cmd));

        assertFalse(validationContext.hasIssues());
        assertEquals("alice-gh", pushContext.getScmUsername());
    }

    @Test
    void permissiveMode_unverifiedIdentity_stillAllowsPush() throws Exception {
        FogwallProvider github = new GitHubProvider("/push");
        when(resolver.resolve(eq(github), eq("corp-user"), any()))
                .thenReturn(Optional.of(userEntryWithScmIdentity("alice", "github", "alice-gh", false)));
        when(permService.isAllowedToPush("alice", "github", "/owner/repo")).thenReturn(true);

        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pushContext = new PushContext();
        pushContext.setPushUser("corp-user");
        pushContext.setRepoSlug("/owner/repo");
        ValidationContext validationContext = new ValidationContext();

        // Default (no identityMode arg) constructor = PERMISSIVE — today's unaffected behavior.
        new CheckUserPushPermissionHook(resolver, permService, validationContext, pushContext, github, null)
                .onPreReceive(rp, List.of(cmd));

        assertFalse(validationContext.hasIssues());
        assertEquals("alice-gh", pushContext.getScmUsername());
    }

    // ---- strict identity mode (#40) — SSH path ----

    @Test
    void strictMode_sshUnverifiedIdentity_blocksPush() throws Exception {
        FogwallProvider github = new GitHubProvider("/push");
        // Key imported from GitHub, but the identity was never OAuth-verified.
        UserEntry alice = userEntryWithSshKey("alice", "github", "alice-gh", false, "github");
        when(permService.isAllowedToPush("alice", "github", "/owner/repo")).thenReturn(true);

        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pushContext = new PushContext();
        pushContext.setRepoSlug("/owner/repo");
        pushContext.setTransport(
                new PushTransport.Ssh(alice, "SHA256:fingerprint", null, ClientLivenessCheck.alwaysConnected()));
        ValidationContext validationContext = new ValidationContext();

        new CheckUserPushPermissionHook(
                        resolver,
                        permService,
                        validationContext,
                        pushContext,
                        github,
                        null,
                        new ImportedKeyIdentityResolver(),
                        ScmOAuthConfig.IdentityMode.STRICT)
                .onPreReceive(rp, List.of(cmd));

        assertTrue(validationContext.hasIssues());
        assertTrue(validationContext.getIssues().get(0).summary().contains("SSH key not imported from github"));
    }

    @Test
    void strictMode_sshVerifiedIdentity_allowsPush() throws Exception {
        FogwallProvider github = new GitHubProvider("/push");
        UserEntry alice = userEntryWithSshKey("alice", "github", "alice-gh", true, "github");
        when(permService.isAllowedToPush("alice", "github", "/owner/repo")).thenReturn(true);

        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pushContext = new PushContext();
        pushContext.setRepoSlug("/owner/repo");
        pushContext.setTransport(
                new PushTransport.Ssh(alice, "SHA256:fingerprint", null, ClientLivenessCheck.alwaysConnected()));
        ValidationContext validationContext = new ValidationContext();

        new CheckUserPushPermissionHook(
                        resolver,
                        permService,
                        validationContext,
                        pushContext,
                        github,
                        null,
                        new ImportedKeyIdentityResolver(),
                        ScmOAuthConfig.IdentityMode.STRICT)
                .onPreReceive(rp, List.of(cmd));

        assertFalse(validationContext.hasIssues());
        assertEquals("alice-gh", pushContext.getScmUsername());
    }

    @Test
    void strictMode_sshHandAddedKey_blocksPush() throws Exception {
        // The behaviour this mode exists for: a key typed into the profile page no longer resolves an SCM identity,
        // even when the provider would confirm it is registered upstream.
        FogwallProvider github = new GitHubProvider("/push");
        UserEntry alice = userEntryWithSshKey("alice", "github", "alice-gh", true, null);
        when(permService.isAllowedToPush("alice", "github", "/owner/repo")).thenReturn(true);

        ValidationContext validationContext = strictSshRun(github, alice);

        assertTrue(validationContext.hasIssues());
        assertTrue(validationContext.getIssues().get(0).summary().contains("SSH key not imported from github"));
    }

    @Test
    void strictMode_keyImportedFromAnotherProvider_blocksPush() throws Exception {
        FogwallProvider github = new GitHubProvider("/push");
        UserEntry alice = userEntryWithSshKey("alice", "github", "alice-gh", true, "gitlab");
        when(permService.isAllowedToPush("alice", "github", "/owner/repo")).thenReturn(true);

        ValidationContext validationContext = strictSshRun(github, alice);

        assertTrue(validationContext.hasIssues());
    }

    @Test
    void strictMode_providerWithoutKeyLookup_stillResolves() throws Exception {
        // Strict mode reads the database, so the SshKeyFingerprintLookup capability permissive mode insists on is not
        // required. A provider that cannot list keys is no longer a reason to refuse an SSH push.
        FogwallProvider noLookup = new BitbucketProvider("/push");
        UserEntry alice =
                userEntryWithSshKey("alice", noLookup.getProviderId(), "alice-bb", true, noLookup.getProviderId());
        when(permService.isAllowedToPush("alice", noLookup.getProviderId(), "/owner/repo"))
                .thenReturn(true);

        ValidationContext validationContext = strictSshRun(noLookup, alice);

        assertFalse(validationContext.hasIssues());
    }

    /** Runs one strict-mode SSH push through the hook and returns the validation context it wrote to. */
    private ValidationContext strictSshRun(FogwallProvider provider, UserEntry user) throws Exception {
        // ServerReceivePackFactory picks the resolver from the mode; strict gets the imported-key one. The enricher is
        // still passed in by tests that assert it is never consulted.
        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pushContext = new PushContext();
        pushContext.setRepoSlug("/owner/repo");
        pushContext.setTransport(
                new PushTransport.Ssh(user, "SHA256:fingerprint", null, ClientLivenessCheck.alwaysConnected()));
        ValidationContext validationContext = new ValidationContext();
        new CheckUserPushPermissionHook(
                        resolver,
                        permService,
                        validationContext,
                        pushContext,
                        provider,
                        null,
                        new ImportedKeyIdentityResolver(),
                        ScmOAuthConfig.IdentityMode.STRICT)
                .onPreReceive(rp, List.of(cmd));
        return validationContext;
    }
}
