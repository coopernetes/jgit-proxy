package com.rbc.fogwall.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rbc.fogwall.user.UserEntry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ChainedPushIdentityResolver}.
 *
 * <p>The chain tries each resolver in order and returns the first non-empty result.
 */
class ChainedPushIdentityResolverTest {

    private static UserEntry entry(String username) {
        return UserEntry.builder()
                .username(username)
                .emails(List.of())
                .scmIdentities(List.of())
                .build();
    }

    // ---- first resolver matches → returned, second not called ----

    @Test
    void firstMatchReturned_secondNotCalled() {
        PushIdentityResolver first = mock(PushIdentityResolver.class);
        PushIdentityResolver second = mock(PushIdentityResolver.class);
        when(first.resolveIdentity(any(), any(), any()))
                .thenReturn(Optional.of(new ResolvedScmIdentity(entry("alice"), "alice-gh")));

        var resolver = new ChainedPushIdentityResolver(List.of(first, second));
        var result = resolver.resolve(null, "me", "tok");

        assertTrue(result.isPresent());
        assertEquals("alice", result.get().getUsername());
        verify(first).resolveIdentity(any(), any(), any());
        verifyNoInteractions(second);
    }

    // ---- first misses, second matches → second's result returned ----

    @Test
    void firstMisses_secondMatches() {
        PushIdentityResolver first = mock(PushIdentityResolver.class);
        PushIdentityResolver second = mock(PushIdentityResolver.class);
        when(first.resolveIdentity(any(), any(), any())).thenReturn(Optional.empty());
        when(second.resolveIdentity(any(), any(), any()))
                .thenReturn(Optional.of(new ResolvedScmIdentity(entry("bob"), "bob-gh")));

        var resolver = new ChainedPushIdentityResolver(List.of(first, second));
        var result = resolver.resolve(null, "me", "tok");

        assertTrue(result.isPresent());
        assertEquals("bob", result.get().getUsername());
    }

    // ---- all resolvers miss → empty ----

    @Test
    void allMiss_returnsEmpty() {
        PushIdentityResolver first = mock(PushIdentityResolver.class);
        PushIdentityResolver second = mock(PushIdentityResolver.class);
        when(first.resolveIdentity(any(), any(), any())).thenReturn(Optional.empty());
        when(second.resolveIdentity(any(), any(), any())).thenReturn(Optional.empty());

        var resolver = new ChainedPushIdentityResolver(List.of(first, second));

        assertTrue(resolver.resolve(null, "me", "tok").isEmpty());
    }

    // ---- empty chain → empty ----

    @Test
    void emptyChain_returnsEmpty() {
        var resolver = new ChainedPushIdentityResolver(List.of());

        assertTrue(resolver.resolve(null, "me", "tok").isEmpty());
    }
}
