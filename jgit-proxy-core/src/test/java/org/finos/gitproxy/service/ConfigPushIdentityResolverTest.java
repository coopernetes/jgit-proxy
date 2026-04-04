package org.finos.gitproxy.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.finos.gitproxy.user.UserEntry;
import org.finos.gitproxy.user.UserStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConfigPushIdentityResolver}. Covers the two-stage lookup: push-username index first, then proxy
 * username fallback.
 */
class ConfigPushIdentityResolverTest {

    UserStore store;
    ConfigPushIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        store = mock(UserStore.class);
        resolver = new ConfigPushIdentityResolver(store);
    }

    private static UserEntry entry(String username) {
        return UserEntry.builder()
                .username(username)
                .emails(java.util.List.of())
                .pushUsernames(java.util.List.of())
                .scmIdentities(java.util.List.of())
                .build();
    }

    // ---- blank / null username → empty ----

    @Test
    void nullUsername_returnsEmpty() {
        assertTrue(resolver.resolve("github.com", null, "tok").isEmpty());
        verifyNoInteractions(store);
    }

    @Test
    void blankUsername_returnsEmpty() {
        assertTrue(resolver.resolve("github.com", "  ", "tok").isEmpty());
        verifyNoInteractions(store);
    }

    // ---- findByPushUsername hits → returned directly ----

    @Test
    void pushUsernameMatch_returnsUser_withoutFallback() {
        UserEntry alice = entry("alice");
        when(store.findByPushUsername("corp-alice")).thenReturn(Optional.of(alice));

        var result = resolver.resolve("github.com", "corp-alice", "ignored-token");

        assertTrue(result.isPresent());
        assertEquals("alice", result.get().getUsername());
        // Should not fall through to findByUsername
        verify(store, never()).findByUsername(anyString());
    }

    // ---- findByPushUsername misses → fallback to findByUsername ----

    @Test
    void pushUsernameNoMatch_fallsBackToUsername() {
        UserEntry bob = entry("bob");
        when(store.findByPushUsername("bob")).thenReturn(Optional.empty());
        when(store.findByUsername("bob")).thenReturn(Optional.of(bob));

        var result = resolver.resolve("github.com", "bob", null);

        assertTrue(result.isPresent());
        assertEquals("bob", result.get().getUsername());
    }

    // ---- neither lookup matches → empty ----

    @Test
    void neitherLookupMatches_returnsEmpty() {
        when(store.findByPushUsername("nobody")).thenReturn(Optional.empty());
        when(store.findByUsername("nobody")).thenReturn(Optional.empty());

        var result = resolver.resolve("github.com", "nobody", "tok");

        assertTrue(result.isEmpty());
    }

    // ---- token is ignored (config-driven) ----

    @Test
    void token_isIgnoredByConfigResolver() {
        UserEntry alice = entry("alice");
        when(store.findByPushUsername("alice")).thenReturn(Optional.of(alice));

        // Should match regardless of token value
        assertTrue(resolver.resolve("github.com", "alice", "any-token").isPresent());
        assertTrue(resolver.resolve("github.com", "alice", null).isPresent());
        assertTrue(resolver.resolve("github.com", "alice", "").isPresent());
    }

    // ---- provider is ignored (config-driven) ----

    @Test
    void provider_isIgnoredByConfigResolver() {
        UserEntry alice = entry("alice");
        when(store.findByPushUsername("alice")).thenReturn(Optional.of(alice));

        assertTrue(resolver.resolve("github.com", "alice", null).isPresent());
        assertTrue(resolver.resolve("gitlab.com", "alice", null).isPresent());
        assertTrue(resolver.resolve(null, "alice", null).isPresent());
    }
}
