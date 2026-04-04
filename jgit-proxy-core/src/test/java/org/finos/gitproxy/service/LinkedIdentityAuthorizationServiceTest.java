package org.finos.gitproxy.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import org.finos.gitproxy.user.UserEntry;
import org.finos.gitproxy.user.UserStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LinkedIdentityAuthorizationService}. Verifies the three-stage resolution: push-username → email
 * → proxy username.
 */
class LinkedIdentityAuthorizationServiceTest {

    UserStore store;
    LinkedIdentityAuthorizationService svc;

    @BeforeEach
    void setUp() {
        store = mock(UserStore.class);
        svc = new LinkedIdentityAuthorizationService(store);
    }

    private static UserEntry entry(String username, String email) {
        return UserEntry.builder()
                .username(username)
                .emails(email != null ? List.of(email) : List.of())
                .pushUsernames(List.of())
                .scmIdentities(List.of())
                .build();
    }

    // ---- isUserAuthorizedToPush ----

    @Test
    void authorizedToPush_whenFoundByPushUsername() {
        when(store.findByPushUsername("corp-alice")).thenReturn(Optional.of(entry("alice", null)));

        assertTrue(svc.isUserAuthorizedToPush("corp-alice", "https://github.com/org/repo"));
    }

    @Test
    void authorizedToPush_whenFoundByEmail() {
        when(store.findByPushUsername("alice@example.com")).thenReturn(Optional.empty());
        when(store.findByEmail("alice@example.com")).thenReturn(Optional.of(entry("alice", "alice@example.com")));

        assertTrue(svc.isUserAuthorizedToPush("alice@example.com", null));
    }

    @Test
    void authorizedToPush_whenFoundByUsername() {
        when(store.findByPushUsername("alice")).thenReturn(Optional.empty());
        when(store.findByEmail("alice")).thenReturn(Optional.empty());
        when(store.findByUsername("alice")).thenReturn(Optional.of(entry("alice", null)));

        assertTrue(svc.isUserAuthorizedToPush("alice", null));
    }

    @Test
    void notAuthorizedToPush_whenNotFoundByAnyLookup() {
        when(store.findByPushUsername("ghost")).thenReturn(Optional.empty());
        when(store.findByEmail("ghost")).thenReturn(Optional.empty());
        when(store.findByUsername("ghost")).thenReturn(Optional.empty());

        assertFalse(svc.isUserAuthorizedToPush("ghost", null));
    }

    // ---- push-username lookup wins before email ----

    @Test
    void pushUsernameFoundFirst_emailNotQueried() {
        when(store.findByPushUsername("corp-alice")).thenReturn(Optional.of(entry("alice", null)));

        assertTrue(svc.isUserAuthorizedToPush("corp-alice", null));
        verify(store, never()).findByEmail(anyString());
        verify(store, never()).findByUsername(anyString());
    }

    // ---- email lookup wins before username ----

    @Test
    void emailFoundSecond_usernameNotQueried() {
        when(store.findByPushUsername("alice@example.com")).thenReturn(Optional.empty());
        when(store.findByEmail("alice@example.com")).thenReturn(Optional.of(entry("alice", "alice@example.com")));

        assertTrue(svc.isUserAuthorizedToPush("alice@example.com", null));
        verify(store, never()).findByUsername(anyString());
    }

    // ---- userExists ----

    @Test
    void userExists_trueWhenFound() {
        when(store.findByPushUsername("alice")).thenReturn(Optional.of(entry("alice", null)));

        assertTrue(svc.userExists("alice"));
    }

    @Test
    void userExists_falseWhenNotFound() {
        when(store.findByPushUsername("nobody")).thenReturn(Optional.empty());
        when(store.findByEmail("nobody")).thenReturn(Optional.empty());
        when(store.findByUsername("nobody")).thenReturn(Optional.empty());

        assertFalse(svc.userExists("nobody"));
    }

    // ---- getUsernameByEmail ----

    @Test
    void getUsernameByEmail_knownEmail_returnsUsername() {
        when(store.findByEmail("alice@example.com")).thenReturn(Optional.of(entry("alice", "alice@example.com")));

        assertEquals("alice", svc.getUsernameByEmail("alice@example.com"));
    }

    @Test
    void getUsernameByEmail_unknownEmail_returnsNull() {
        when(store.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertNull(svc.getUsernameByEmail("nobody@example.com"));
    }
}
