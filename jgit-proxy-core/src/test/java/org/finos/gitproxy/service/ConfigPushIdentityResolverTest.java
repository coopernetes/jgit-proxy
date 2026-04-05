package org.finos.gitproxy.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.finos.gitproxy.user.UserEntry;
import org.finos.gitproxy.user.UserStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConfigPushIdentityResolver}. The provider and token are ignored; only the push username is used
 * to look up the proxy user by username.
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
                .scmIdentities(java.util.List.of())
                .build();
    }

    // ---- blank / null username → empty ----

    @Test
    void nullUsername_returnsEmpty() {
        assertTrue(resolver.resolve(null, null, "tok").isEmpty());
        verifyNoInteractions(store);
    }

    @Test
    void blankUsername_returnsEmpty() {
        assertTrue(resolver.resolve(null, "  ", "tok").isEmpty());
        verifyNoInteractions(store);
    }

    // ---- findByUsername hits → returned ----

    @Test
    void usernameMatch_returnsUser() {
        UserEntry alice = entry("alice");
        when(store.findByUsername("alice")).thenReturn(Optional.of(alice));

        var result = resolver.resolve(null, "alice", "ignored-token");

        assertTrue(result.isPresent());
        assertEquals("alice", result.get().getUsername());
    }

    // ---- findByUsername misses → empty ----

    @Test
    void usernameNoMatch_returnsEmpty() {
        when(store.findByUsername("nobody")).thenReturn(Optional.empty());

        var result = resolver.resolve(null, "nobody", "tok");

        assertTrue(result.isEmpty());
    }

    // ---- token is ignored (config-driven) ----

    @Test
    void token_isIgnoredByConfigResolver() {
        UserEntry alice = entry("alice");
        when(store.findByUsername("alice")).thenReturn(Optional.of(alice));

        assertTrue(resolver.resolve(null, "alice", "any-token").isPresent());
        assertTrue(resolver.resolve(null, "alice", null).isPresent());
        assertTrue(resolver.resolve(null, "alice", "").isPresent());
    }

    // ---- provider is ignored (config-driven) ----

    @Test
    void provider_isIgnoredByConfigResolver() {
        UserEntry alice = entry("alice");
        when(store.findByUsername("alice")).thenReturn(Optional.of(alice));

        assertTrue(resolver.resolve(null, "alice", null).isPresent());
    }
}
