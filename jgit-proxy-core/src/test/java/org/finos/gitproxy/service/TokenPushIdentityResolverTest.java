package org.finos.gitproxy.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.provider.ScmUserInfo;
import org.finos.gitproxy.provider.TokenIdentityProvider;
import org.finos.gitproxy.user.ScmIdentity;
import org.finos.gitproxy.user.UserEntry;
import org.finos.gitproxy.user.UserStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TokenPushIdentityResolver}.
 *
 * <p>The resolver calls the provider's {@link TokenIdentityProvider#fetchScmIdentity} to get the SCM login, then looks
 * up the proxy user via {@code user_scm_identities} or email fallback.
 */
class TokenPushIdentityResolverTest {

    /** Combined interface so Mockito can mock a provider that supports token identity lookup. */
    interface TokenProvider extends GitProxyProvider, TokenIdentityProvider {}

    UserStore store;
    TokenPushIdentityResolver resolver;
    TokenProvider provider;

    @BeforeEach
    void setUp() {
        store = mock(UserStore.class);
        provider = mock(TokenProvider.class);
        when(provider.getName()).thenReturn("github");
        when(provider.getUri()).thenReturn(URI.create("https://github.com"));
        resolver = new TokenPushIdentityResolver(store);
    }

    private static UserEntry entry(String username, String... emails) {
        return UserEntry.builder()
                .username(username)
                .emails(List.of(emails))
                .scmIdentities(List.of(ScmIdentity.builder()
                        .provider("github")
                        .username(username + "-gh")
                        .build()))
                .build();
    }

    // ---- provider does not implement TokenIdentityProvider → empty ----

    @Test
    void nonTokenProvider_returnsEmpty() {
        GitProxyProvider plainProvider = mock(GitProxyProvider.class);
        when(plainProvider.getName()).thenReturn("generic");

        var result = resolver.resolve(plainProvider, "user", "token");

        assertTrue(result.isEmpty());
        verifyNoInteractions(store);
    }

    // ---- null provider → empty ----

    @Test
    void nullProvider_returnsEmpty() {
        assertTrue(resolver.resolve(null, "user", "token").isEmpty());
        verifyNoInteractions(store);
    }

    // ---- fetchScmIdentity returns empty (bad token / API error) → empty ----

    @Test
    void fetchScmIdentityEmpty_returnsEmpty() {
        when(provider.fetchScmIdentity(anyString(), anyString())).thenReturn(Optional.empty());

        var result = resolver.resolve(provider, "me", "bad-token");

        assertTrue(result.isEmpty());
        verifyNoInteractions(store);
    }

    // ---- scm identity lookup hits → user returned ----

    @Test
    void scmIdentityMatch_returnsUser() {
        UserEntry alice = entry("alice", "alice@example.com");
        when(provider.fetchScmIdentity(anyString(), eq("good-token")))
                .thenReturn(Optional.of(new ScmUserInfo("alice-gh", Optional.empty())));
        when(store.findByScmIdentity("github", "alice-gh")).thenReturn(Optional.of(alice));

        var result = resolver.resolve(provider, "me", "good-token");

        assertTrue(result.isPresent());
        assertEquals("alice", result.get().getUsername());
        verify(store).findByScmIdentity("github", "alice-gh");
        verify(store, never()).findByEmail(any());
    }

    // ---- scm identity miss, email fallback hits → user returned ----

    @Test
    void scmIdentityMiss_emailFallback_returnsUser() {
        UserEntry alice = entry("alice", "alice@example.com");
        when(provider.fetchScmIdentity(anyString(), anyString()))
                .thenReturn(Optional.of(new ScmUserInfo("alice-gh", Optional.of("alice@example.com"))));
        when(store.findByScmIdentity("github", "alice-gh")).thenReturn(Optional.empty());
        when(store.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));

        var result = resolver.resolve(provider, "me", "token");

        assertTrue(result.isPresent());
        assertEquals("alice", result.get().getUsername());
        verify(store).findByScmIdentity("github", "alice-gh");
        verify(store).findByEmail("alice@example.com");
    }

    // ---- both lookups miss → empty ----

    @Test
    void bothLookupsMiss_returnsEmpty() {
        when(provider.fetchScmIdentity(anyString(), anyString()))
                .thenReturn(Optional.of(new ScmUserInfo("unknown-gh", Optional.of("unknown@example.com"))));
        when(store.findByScmIdentity("github", "unknown-gh")).thenReturn(Optional.empty());
        when(store.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        var result = resolver.resolve(provider, "me", "token");

        assertTrue(result.isEmpty());
    }

    // ---- no email in ScmUserInfo and scm identity misses → empty (no NPE) ----

    @Test
    void noEmailInScmInfo_scmIdentityMiss_returnsEmpty() {
        when(provider.fetchScmIdentity(anyString(), anyString()))
                .thenReturn(Optional.of(new ScmUserInfo("nobody-gh", Optional.empty())));
        when(store.findByScmIdentity("github", "nobody-gh")).thenReturn(Optional.empty());

        var result = resolver.resolve(provider, "me", "token");

        assertTrue(result.isEmpty());
        // findByEmail should be called with null (Optional.empty().orElse(null))
        verify(store).findByEmail(null);
    }
}
