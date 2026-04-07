package org.finos.gitproxy.dashboard.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.finos.gitproxy.user.MutableUserStore;
import org.finos.gitproxy.user.ScmIdentity;
import org.finos.gitproxy.user.UserEntry;
import org.finos.gitproxy.user.UserStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String username) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        username, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    // ── MutableUserStore (CompositeUserStore / JdbcUserStore) ────────────────────

    @Nested
    class WithMutableStore {

        @InjectMocks
        AuthController controller;

        @Mock
        MutableUserStore userStore; // injected into the UserStore field

        @Test
        void mutableStore_returnsVerifiedEmailsAndIdentities() {
            loginAs("alice");
            when(userStore.findByUsername("alice"))
                    .thenReturn(
                            Optional.of(UserEntry.builder().username("alice").build()));
            when(userStore.findEmailsWithVerified("alice"))
                    .thenReturn(List.of(Map.of("email", "alice@example.com", "verified", true, "locked", false)));
            when(userStore.findScmIdentitiesWithVerified("alice"))
                    .thenReturn(List.of(Map.of("provider", "github", "username", "alice-gh", "verified", true)));

            var result = controller.me();

            assertEquals("alice", result.get("username"));
            @SuppressWarnings("unchecked")
            var emails = (List<Map<String, Object>>) result.get("emails");
            assertEquals(1, emails.size());
            assertEquals(true, emails.get(0).get("verified"));
            @SuppressWarnings("unchecked")
            var ids = (List<Map<String, Object>>) result.get("scmIdentities");
            assertEquals("alice-gh", ids.get(0).get("username"));
        }

        @Test
        void mutableStore_unknownUser_returnsEmptyLists() {
            loginAs("ghost");
            when(userStore.findByUsername("ghost")).thenReturn(Optional.empty());

            var result = controller.me();

            assertEquals("ghost", result.get("username"));
            assertEquals(List.of(), result.get("emails"));
            assertEquals(List.of(), result.get("scmIdentities"));
        }

        @Test
        void authoritiesIncludedInResponse() {
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(
                            "alice",
                            null,
                            List.of(
                                    new SimpleGrantedAuthority("ROLE_USER"),
                                    new SimpleGrantedAuthority("ROLE_ADMIN"))));
            when(userStore.findByUsername("alice"))
                    .thenReturn(
                            Optional.of(UserEntry.builder().username("alice").build()));
            when(userStore.findEmailsWithVerified("alice")).thenReturn(List.of());
            when(userStore.findScmIdentitiesWithVerified("alice")).thenReturn(List.of());

            @SuppressWarnings("unchecked")
            var authorities = (List<String>) controller.me().get("authorities");

            assertTrue(authorities.contains("ROLE_ADMIN"));
            assertTrue(authorities.contains("ROLE_USER"));
        }
    }

    // ── Read-only UserStore (StaticUserStore) ────────────────────────────────────

    @Nested
    class WithStaticStore {

        @InjectMocks
        AuthController controller;

        // Plain UserStore (not MutableUserStore) — simulates StaticUserStore
        @Mock
        UserStore userStore;

        @Test
        void staticStore_emailsUnverifiedAndLocal() {
            loginAs("bob");
            var user = UserEntry.builder()
                    .username("bob")
                    .emails(List.of("bob@example.com"))
                    .scmIdentities(List.of(ScmIdentity.builder()
                            .provider("github")
                            .username("bob-gh")
                            .build()))
                    .build();
            when(userStore.findByUsername("bob")).thenReturn(Optional.of(user));

            var result = controller.me();

            @SuppressWarnings("unchecked")
            var emails = (List<Map<String, Object>>) result.get("emails");
            assertEquals(1, emails.size());
            assertEquals("bob@example.com", emails.get(0).get("email"));
            assertEquals(false, emails.get(0).get("verified"));
            assertEquals(false, emails.get(0).get("locked"));
            assertEquals("local", emails.get(0).get("source"));

            @SuppressWarnings("unchecked")
            var ids = (List<Map<String, Object>>) result.get("scmIdentities");
            assertEquals("bob-gh", ids.get(0).get("username"));
            assertEquals(false, ids.get(0).get("verified"));
        }

        @Test
        void noAuthentication_returnsEmptyProfile() {
            // No security context set — simulates anonymous/unauthenticated
            var result = controller.me();

            assertEquals("", result.get("username"));
            assertEquals(List.of(), result.get("emails"));
            assertEquals(List.of(), result.get("scmIdentities"));
            assertEquals(List.of(), result.get("authorities"));
        }
    }
}
