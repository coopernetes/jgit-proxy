package org.finos.gitproxy.dashboard.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import org.finos.gitproxy.user.LockedByConfigException;
import org.finos.gitproxy.user.LockedEmailException;
import org.finos.gitproxy.user.MutableUserStore;
import org.finos.gitproxy.user.ScmIdentityConflictException;
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

    // MutableUserStore extends UserStore — injected into the UserStore field;
    // the instanceof pattern in the controller will match.
    @Mock
    MutableUserStore userStore;

    @BeforeEach
    void setupSecurityContext() {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("alice");
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(ctx);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ── POST /api/me/emails ──────────────────────────────────────────────────────

    @Test
    void addEmail_configUser_returns403() {
        when(userStore.findByEmail("new@example.com")).thenReturn(Optional.empty());
        doThrow(new LockedByConfigException("alice")).when(userStore).addEmail(eq("alice"), eq("new@example.com"));

        var resp = controller.addEmail(Map.of("email", "new@example.com"));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void addEmail_success_returns200() {
        when(userStore.findByEmail("new@example.com")).thenReturn(Optional.empty());

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
}
