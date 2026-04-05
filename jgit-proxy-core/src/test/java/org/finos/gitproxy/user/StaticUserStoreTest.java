package org.finos.gitproxy.user;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StaticUserStoreTest {

    StaticUserStore store;
    UserEntry alice;
    UserEntry bob;

    @BeforeEach
    void setUp() {
        alice = UserEntry.builder()
                .username("alice")
                .passwordHash("{noop}pw")
                .emails(List.of("alice@example.com", "alice@corp.com"))
                .scmIdentities(List.of(
                        ScmIdentity.builder()
                                .provider("github")
                                .username("alice-gh")
                                .build(),
                        ScmIdentity.builder()
                                .provider("gitlab")
                                .username("alice-gl")
                                .build()))
                .build();
        bob = UserEntry.builder()
                .username("bob")
                .passwordHash("{noop}pw")
                .emails(List.of("bob@example.com"))
                .scmIdentities(List.of(ScmIdentity.builder()
                        .provider("github")
                        .username("bob-gh")
                        .build()))
                .build();
        store = new StaticUserStore(List.of(alice, bob));
    }

    // ---- findByUsername ----

    @Test
    void findByUsername_knownUser_returnsUser() {
        var result = store.findByUsername("alice");
        assertTrue(result.isPresent());
        assertEquals("alice", result.get().getUsername());
    }

    @Test
    void findByUsername_unknownUser_returnsEmpty() {
        assertTrue(store.findByUsername("nobody").isEmpty());
    }

    // ---- findByEmail ----

    @Test
    void findByEmail_primaryEmail_returnsUser() {
        var result = store.findByEmail("alice@example.com");
        assertTrue(result.isPresent());
        assertEquals("alice", result.get().getUsername());
    }

    @Test
    void findByEmail_secondaryEmail_returnsUser() {
        var result = store.findByEmail("alice@corp.com");
        assertTrue(result.isPresent());
        assertEquals("alice", result.get().getUsername());
    }

    @Test
    void findByEmail_caseInsensitive() {
        assertTrue(store.findByEmail("ALICE@EXAMPLE.COM").isPresent());
        assertTrue(store.findByEmail("Alice@Example.Com").isPresent());
    }

    @Test
    void findByEmail_unknownEmail_returnsEmpty() {
        assertTrue(store.findByEmail("nobody@example.com").isEmpty());
    }

    @Test
    void findByEmail_null_returnsEmpty() {
        assertTrue(store.findByEmail(null).isEmpty());
    }

    // ---- findByScmIdentity ----

    @Test
    void findByScmIdentity_knownProviderAndLogin_returnsUser() {
        var result = store.findByScmIdentity("github", "alice-gh");
        assertTrue(result.isPresent());
        assertEquals("alice", result.get().getUsername());
    }

    @Test
    void findByScmIdentity_differentProvider_returnsUser() {
        var result = store.findByScmIdentity("gitlab", "alice-gl");
        assertTrue(result.isPresent());
        assertEquals("alice", result.get().getUsername());
    }

    @Test
    void findByScmIdentity_wrongProvider_returnsEmpty() {
        // alice-gh is registered for github, not gitlab
        assertTrue(store.findByScmIdentity("gitlab", "alice-gh").isEmpty());
    }

    @Test
    void findByScmIdentity_unknownLogin_returnsEmpty() {
        assertTrue(store.findByScmIdentity("github", "nobody").isEmpty());
    }

    @Test
    void findByScmIdentity_null_returnsEmpty() {
        assertTrue(store.findByScmIdentity(null, "alice-gh").isEmpty());
        assertTrue(store.findByScmIdentity("github", null).isEmpty());
    }

    @Test
    void findByScmIdentity_routesCorrectly() {
        assertEquals(
                "alice", store.findByScmIdentity("github", "alice-gh").get().getUsername());
        assertEquals("bob", store.findByScmIdentity("github", "bob-gh").get().getUsername());
        assertTrue(store.findByScmIdentity("github", "charlie-gh").isEmpty());
    }

    // ---- findAll ----

    @Test
    void findAll_returnsAllUsers() {
        var all = store.findAll();
        assertEquals(2, all.size());
    }

    // ---- empty store ----

    @Test
    void emptyStore_allLookupsReturnEmpty() {
        StaticUserStore empty = new StaticUserStore(List.of());
        assertTrue(empty.findByUsername("anyone").isEmpty());
        assertTrue(empty.findByEmail("anyone@example.com").isEmpty());
        assertTrue(empty.findByScmIdentity("github", "anyone").isEmpty());
        assertEquals(0, empty.findAll().size());
    }
}
