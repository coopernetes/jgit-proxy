package org.finos.gitproxy.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.user.ScmIdentity;
import org.finos.gitproxy.user.UserEntry;
import org.finos.gitproxy.user.UserStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CachingTokenPushIdentityResolverTest {

    PushIdentityResolver delegate;
    JdbcScmTokenCache cache;
    UserStore userStore;
    CachingTokenPushIdentityResolver resolver;
    GitProxyProvider provider;

    @BeforeEach
    void setUp() {
        delegate = mock(PushIdentityResolver.class);
        cache = mock(JdbcScmTokenCache.class);
        userStore = mock(UserStore.class);
        provider = mock(GitProxyProvider.class);
        when(provider.getName()).thenReturn("github");
        when(provider.getUri()).thenReturn(URI.create("https://github.com"));
        resolver = new CachingTokenPushIdentityResolver(delegate, cache, userStore);
    }

    private static UserEntry entry(String username) {
        return UserEntry.builder()
                .username(username)
                .emails(List.of())
                .scmIdentities(List.of(ScmIdentity.builder()
                        .provider("github")
                        .username(username + "-gh")
                        .build()))
                .build();
    }

    // ---- cache hit — delegate is not called ----

    @Test
    void cacheHit_returnsCachedUser_delegateNotCalled() {
        UserEntry alice = entry("alice");
        when(cache.lookup(eq("github"), anyString())).thenReturn(Optional.of("alice"));
        when(userStore.findByUsername("alice")).thenReturn(Optional.of(alice));

        var result = resolver.resolve(provider, "me", "good-token");

        assertTrue(result.isPresent());
        assertEquals("alice", result.get().getUsername());
        verifyNoInteractions(delegate);
        verify(cache, never()).store(any(), any(), any());
    }

    // ---- cache miss, delegate resolves → stored in cache ----

    @Test
    void cacheMiss_delegateResolves_storesAndReturns() {
        UserEntry alice = entry("alice");
        when(cache.lookup(eq("github"), anyString())).thenReturn(Optional.empty());
        when(delegate.resolve(provider, "me", "good-token")).thenReturn(Optional.of(alice));

        var result = resolver.resolve(provider, "me", "good-token");

        assertTrue(result.isPresent());
        assertEquals("alice", result.get().getUsername());
        verify(cache).store(eq("github"), anyString(), eq("alice"));
        verifyNoMoreInteractions(userStore);
    }

    // ---- cache miss, delegate returns empty → nothing stored ----

    @Test
    void cacheMiss_delegateEmpty_nothingStored() {
        when(cache.lookup(eq("github"), anyString())).thenReturn(Optional.empty());
        when(delegate.resolve(provider, "me", "bad-token")).thenReturn(Optional.empty());

        var result = resolver.resolve(provider, "me", "bad-token");

        assertTrue(result.isEmpty());
        verify(cache, never()).store(any(), any(), any());
    }

    // ---- same token produces the same hash (deterministic) ----

    @Test
    void sameToken_lookupCalledWithSameHash() {
        when(cache.lookup(eq("github"), anyString())).thenReturn(Optional.empty());
        when(delegate.resolve(any(), any(), any())).thenReturn(Optional.empty());

        resolver.resolve(provider, "me", "my-token");
        resolver.resolve(provider, "me", "my-token");

        // Capture both hash arguments and assert they are equal
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(cache, times(2)).lookup(eq("github"), captor.capture());
        assertEquals(captor.getAllValues().get(0), captor.getAllValues().get(1));
    }

    // ---- different tokens produce different hashes ----

    @Test
    void differentTokens_differentHashes() {
        when(cache.lookup(eq("github"), anyString())).thenReturn(Optional.empty());
        when(delegate.resolve(any(), any(), any())).thenReturn(Optional.empty());

        resolver.resolve(provider, "me", "token-a");
        resolver.resolve(provider, "me", "token-b");

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(cache, times(2)).lookup(eq("github"), captor.capture());
        assertNotEquals(captor.getAllValues().get(0), captor.getAllValues().get(1));
    }

    // ---- null provider — falls through to delegate directly ----

    @Test
    void nullProvider_delegatedDirectly() {
        when(delegate.resolve(null, "me", "token")).thenReturn(Optional.empty());

        var result = resolver.resolve(null, "me", "token");

        assertTrue(result.isEmpty());
        verifyNoInteractions(cache);
    }

    // ---- null token — falls through to delegate directly ----

    @Test
    void nullToken_delegatedDirectly() {
        when(delegate.resolve(provider, "me", null)).thenReturn(Optional.empty());

        var result = resolver.resolve(provider, "me", null);

        assertTrue(result.isEmpty());
        verifyNoInteractions(cache);
    }
}
