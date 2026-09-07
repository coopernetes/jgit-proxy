package com.rbc.fogwall.dashboard.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rbc.fogwall.config.ScmOAuthConfig;
import com.rbc.fogwall.permission.GroupPermissionStore;
import com.rbc.fogwall.permission.PermissionGroup;
import com.rbc.fogwall.permission.RepoPermission;
import com.rbc.fogwall.permission.RepoPermissionService;
import com.rbc.fogwall.user.EmailConflictException;
import com.rbc.fogwall.user.LockedByConfigException;
import com.rbc.fogwall.user.LockedEmailException;
import com.rbc.fogwall.user.ScmIdentityConflictException;
import com.rbc.fogwall.user.SshKeyConflictException;
import com.rbc.fogwall.user.SshKeyEntry;
import com.rbc.fogwall.user.UserStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class ProfileControllerTest {

    @InjectMocks
    ProfileController controller;

    @Mock
    UserStore userStore;

    @Mock
    RepoPermissionService permissionService;

    @Mock
    ScmOAuthConfig scmOAuthConfig;

    @BeforeEach
    void setupSecurityContext() {
        Authentication auth = mock(Authentication.class);
        lenient().when(auth.getName()).thenReturn("alice");
        SecurityContext ctx = mock(SecurityContext.class);
        lenient().when(ctx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(ctx);

        lenient().when(scmOAuthConfig.getIdentityMode()).thenReturn(ScmOAuthConfig.IdentityMode.PERMISSIVE);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ── POST /api/me/emails ──────────────────────────────────────────────────────

    @Test
    void addEmail_configUser_returns403() {
        doThrow(new LockedByConfigException("alice")).when(userStore).addEmail(eq("alice"), eq("new@example.com"));

        var resp = controller.addEmail(Map.of("email", "new@example.com"));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void addEmail_conflict_returns409() {
        doThrow(new EmailConflictException("new@example.com", "bob"))
                .when(userStore)
                .addEmail(eq("alice"), eq("new@example.com"));

        var resp = controller.addEmail(Map.of("email", "new@example.com"));

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
    }

    @Test
    void addEmail_success_returns200() {
        var resp = controller.addEmail(Map.of("email", "new@example.com"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(userStore).addEmail("alice", "new@example.com");
    }

    // ── DELETE /api/me/emails/{email} ────────────────────────────────────────────

    @Test
    void removeEmail_configUser_returns403() {
        doThrow(new LockedByConfigException("alice")).when(userStore).removeEmail(eq("alice"), any());

        var resp = controller.removeEmail("me@example.com");

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void removeEmail_idpLocked_returns403() {
        doThrow(new LockedEmailException("me@example.com")).when(userStore).removeEmail(eq("alice"), any());

        var resp = controller.removeEmail("me@example.com");

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void removeEmail_success_returns204() {
        var resp = controller.removeEmail("me@example.com");
        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
    }

    // ── POST /api/me/identities ──────────────────────────────────────────────────

    @Test
    void addScmIdentity_configUser_returns403() {
        doThrow(new LockedByConfigException("alice"))
                .when(userStore)
                .addScmIdentity(eq("alice"), eq("github"), eq("alice-gh"));

        var resp = controller.addScmIdentity(Map.of("provider", "github", "username", "alice-gh"));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void addScmIdentity_conflict_returns409() {
        doThrow(new ScmIdentityConflictException("github", "alice-gh", "other-user"))
                .when(userStore)
                .addScmIdentity(eq("alice"), eq("github"), eq("alice-gh"));

        var resp = controller.addScmIdentity(Map.of("provider", "github", "username", "alice-gh"));

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
    }

    @Test
    void addScmIdentity_success_returns200() {
        var resp = controller.addScmIdentity(Map.of("provider", "github", "username", "alice-gh"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(userStore).addScmIdentity("alice", "github", "alice-gh");
    }

    @Test
    void addScmIdentity_strictMode_returns403_andNeverCallsStore() {
        when(scmOAuthConfig.getIdentityMode()).thenReturn(ScmOAuthConfig.IdentityMode.STRICT);

        var resp = controller.addScmIdentity(Map.of("provider", "github", "username", "alice-gh"));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        verify(userStore, never()).addScmIdentity(any(), any(), any());
    }

    @Test
    void addEmail_strictMode_stillAllowed() {
        // addEmail never consults ScmOAuthConfig at all — strict identity mode governs push-time SCM identity
        // resolution, not commit-author-email verification, so email claims are unaffected regardless of mode.
        var resp = controller.addEmail(Map.of("email", "new@example.com"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(userStore).addEmail("alice", "new@example.com");
    }

    // ── DELETE /api/me/identities/{provider}/{scmUsername} ──────────────────────

    @Test
    void removeScmIdentity_configUser_returns403() {
        doThrow(new LockedByConfigException("alice"))
                .when(userStore)
                .removeScmIdentity(eq("alice"), eq("github"), eq("alice-gh"));

        var resp = controller.removeScmIdentity("github", "alice-gh");

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void removeScmIdentity_success_returns204() {
        var resp = controller.removeScmIdentity("github", "alice-gh");
        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
    }

    // ── SSH key management (GET/POST/DELETE /api/me/ssh-keys) ───────────────────
    // Regression coverage for #429-adjacent DELETE /api/me/ssh-keys/{id} returning an unhandled 400/500 instead of
    // a clean 403 when removing a config-locked key - CompositeUserStore.removeSshKey now throws
    // LockedByConfigException (matching removeEmail/removeScmIdentity) instead of a bare IllegalStateException.

    private static final String TEST_SSH_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIIQiTzhWg82OVGUGpUMctA7FoBSZteJQ5R/TPaVfCC95";

    @Test
    @SuppressWarnings("unchecked")
    void listSshKeys_returnsRegisteredKeys() {
        SshKeyEntry key = SshKeyEntry.builder()
                .id("key-1")
                .username("alice")
                .fingerprint("SHA256:abc")
                .publicKey(TEST_SSH_KEY)
                .label("laptop")
                .createdAt(Instant.EPOCH)
                .build();
        when(userStore.findSshKeys("alice")).thenReturn(List.of(key));

        var resp = controller.listSshKeys();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        var body = (List<Map<String, String>>) resp.getBody();
        assertEquals(1, body.size());
        assertEquals("key-1", body.get(0).get("id"));
        assertEquals("SHA256:abc", body.get(0).get("fingerprint"));
    }

    @Test
    void addSshKey_missingPublicKey_returns400() {
        var resp = controller.addSshKey(Map.of());
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void addSshKey_malformedPublicKey_returns400() {
        var resp = controller.addSshKey(Map.of("publicKey", "not-an-ssh-key"));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void addSshKey_conflict_returns409() {
        when(userStore.addSshKey(eq("alice"), any(), any(), any()))
                .thenThrow(new SshKeyConflictException("SHA256:abc", "bob"));

        var resp = controller.addSshKey(Map.of("publicKey", TEST_SSH_KEY, "label", "laptop"));

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
    }

    @Test
    void addSshKey_success_returns200() {
        SshKeyEntry entry = SshKeyEntry.builder()
                .id("key-1")
                .username("alice")
                .fingerprint("SHA256:abc")
                .publicKey(TEST_SSH_KEY)
                .label("laptop")
                .createdAt(Instant.EPOCH)
                .build();
        when(userStore.addSshKey(eq("alice"), any(), any(), eq("laptop"))).thenReturn(entry);

        var resp = controller.addSshKey(Map.of("publicKey", TEST_SSH_KEY, "label", "laptop"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(userStore).addSshKey(eq("alice"), any(), any(), eq("laptop"));
    }

    @Test
    void removeSshKey_configLocked_returns403() {
        doThrow(new LockedByConfigException("alice")).when(userStore).removeSshKey(eq("alice"), eq("key-1"));

        var resp = controller.removeSshKey("key-1");

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void removeSshKey_success_returns204() {
        var resp = controller.removeSshKey("key-1");

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
        verify(userStore).removeSshKey("alice", "key-1");
    }

    // ── GET /api/me/permissions ──────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void getPermissions_noGroupStore_returnsDirectAndEmptyGroups() {
        RepoPermission direct = RepoPermission.builder()
                .username("alice")
                .provider("github")
                .value("/acme/repo")
                .grant(RepoPermission.Grant.PUSH)
                .source(RepoPermission.Source.DB)
                .build();
        when(permissionService.findByUsername("alice")).thenReturn(List.of(direct));
        when(permissionService.getGroupStore()).thenReturn(null);

        var resp = controller.getPermissions();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        var body = (Map<String, Object>) resp.getBody();
        assertEquals(List.of(direct), body.get("direct"));
        assertEquals(List.of(), body.get("groups"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getPermissions_withGroupStore_returnsMergedResult() {
        GroupPermissionStore groupStore = mock(GroupPermissionStore.class);
        PermissionGroup g = PermissionGroup.builder()
                .name("devs")
                .source(PermissionGroup.Source.DB)
                .build();
        when(permissionService.findByUsername("alice")).thenReturn(List.of());
        when(permissionService.getGroupStore()).thenReturn(groupStore);
        when(groupStore.findGroupIdsForUser("alice")).thenReturn(List.of(g.getId()));
        when(groupStore.findGroupById(g.getId())).thenReturn(Optional.of(g));
        when(groupStore.findRulesForGroup(g.getId())).thenReturn(List.of());

        var resp = controller.getPermissions();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        var body = (Map<String, Object>) resp.getBody();
        assertEquals(List.of(), body.get("direct"));
        assertEquals(1, ((List<?>) body.get("groups")).size());
    }
}
