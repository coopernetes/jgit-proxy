package org.finos.gitproxy.dashboard.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.user.LockedByConfigException;
import org.finos.gitproxy.user.MutableUserStore;
import org.finos.gitproxy.user.ScmIdentityConflictException;
import org.finos.gitproxy.user.UserEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @InjectMocks
    UserController controller;

    @Mock
    MutableUserStore userStore;

    @Mock
    PushStore pushStore;

    @Mock
    PasswordEncoder passwordEncoder;

    private static final UserEntry ALICE = UserEntry.builder()
            .username("alice")
            .passwordHash("{noop}pw")
            .emails(List.of())
            .scmIdentities(List.of())
            .roles(List.of("USER"))
            .build();

    // ── POST /api/users/{username}/emails ────────────────────────────────────────

    @Test
    void addEmail_configUser_returns403() {
        when(userStore.findByUsername("alice")).thenReturn(Optional.of(ALICE));
        doThrow(new LockedByConfigException("alice")).when(userStore).addEmail(eq("alice"), any());

        var resp = controller.addEmail("alice", new UserController.AddEmailRequest("new@example.com"));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void addEmail_success_returns201() {
        when(userStore.findByUsername("alice")).thenReturn(Optional.of(ALICE));

        var resp = controller.addEmail("alice", new UserController.AddEmailRequest("new@example.com"));

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        verify(userStore).addEmail("alice", "new@example.com");
    }

    @Test
    void addEmail_unknownUser_returns404() {
        when(userStore.findByUsername("nobody")).thenReturn(Optional.empty());

        var resp = controller.addEmail("nobody", new UserController.AddEmailRequest("x@example.com"));

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // ── DELETE /api/users/{username}/emails/{email} ──────────────────────────────

    @Test
    void removeEmail_configUser_returns403() {
        when(userStore.findByUsername("alice")).thenReturn(Optional.of(ALICE));
        doThrow(new LockedByConfigException("alice")).when(userStore).removeEmail(eq("alice"), any());

        var resp = controller.removeEmail("alice", "old@example.com");

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void removeEmail_success_returns204() {
        when(userStore.findByUsername("alice")).thenReturn(Optional.of(ALICE));

        var resp = controller.removeEmail("alice", "old@example.com");

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
    }

    // ── POST /api/users/{username}/identities ────────────────────────────────────

    @Test
    void addIdentity_configUser_returns403() {
        when(userStore.findByUsername("alice")).thenReturn(Optional.of(ALICE));
        doThrow(new LockedByConfigException("alice"))
                .when(userStore)
                .addScmIdentity(eq("alice"), eq("github"), eq("alice-gh"));

        var resp = controller.addIdentity("alice", new UserController.ScmIdentityRequest("github", "alice-gh"));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void addIdentity_conflict_returns409() {
        when(userStore.findByUsername("alice")).thenReturn(Optional.of(ALICE));
        doThrow(new ScmIdentityConflictException("github", "alice-gh", "other"))
                .when(userStore)
                .addScmIdentity(eq("alice"), eq("github"), eq("alice-gh"));

        var resp = controller.addIdentity("alice", new UserController.ScmIdentityRequest("github", "alice-gh"));

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
    }

    @Test
    void addIdentity_success_returns201() {
        when(userStore.findByUsername("alice")).thenReturn(Optional.of(ALICE));

        var resp = controller.addIdentity("alice", new UserController.ScmIdentityRequest("github", "alice-gh"));

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        verify(userStore).addScmIdentity("alice", "github", "alice-gh");
    }

    // ── DELETE /api/users/{username}/identities/{provider}/{scmUsername} ─────────

    @Test
    void removeIdentity_configUser_returns403() {
        when(userStore.findByUsername("alice")).thenReturn(Optional.of(ALICE));
        doThrow(new LockedByConfigException("alice"))
                .when(userStore)
                .removeScmIdentity(eq("alice"), eq("github"), eq("alice-gh"));

        var resp = controller.removeIdentity("alice", "github", "alice-gh");

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void removeIdentity_success_returns204() {
        when(userStore.findByUsername("alice")).thenReturn(Optional.of(ALICE));

        var resp = controller.removeIdentity("alice", "github", "alice-gh");

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
    }
}
