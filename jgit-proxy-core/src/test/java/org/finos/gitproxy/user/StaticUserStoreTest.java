package org.finos.gitproxy.user;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StaticUserStore}, focusing on push-username index lookup and the three-way query surface
 * (username, email, push-username).
 */
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
                .pushUsernames(List.of("alice", "alice-corp"))
                .scmIdentities(List.of())
                .build();
        bob = UserEntry.builder()
                .username("bob")
                .passwordHash("{noop}pw")
                .emails(List.of("bob@example.com"))
                .pushUsernames(List.of("bob-github"))
                .scmIdentities(List.of())
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

    // ---- findByPushUsername ----

    @Test
    void findByPushUsername_explicitPushUsername_returnsUser() {
        var result = store.findByPushUsername("alice-corp");
        assertTrue(result.isPresent());
        assertEquals("alice", result.get().getUsername());
    }

    @Test
    void findByPushUsername_proxyUsernameInList_returnsUser() {
        // "alice" appears explicitly in pushUsernames
        var result = store.findByPushUsername("alice");
        assertTrue(result.isPresent());
        assertEquals("alice", result.get().getUsername());
    }

    @Test
    void findByPushUsername_bobGithubHandle_returnsUser() {
        var result = store.findByPushUsername("bob-github");
        assertTrue(result.isPresent());
        assertEquals("bob", result.get().getUsername());
    }

    @Test
    void findByPushUsername_notInList_returnsEmpty() {
        // "bob" is not in bob's pushUsernames (only "bob-github" is)
        assertTrue(store.findByPushUsername("bob").isEmpty());
    }

    @Test
    void findByPushUsername_null_returnsEmpty() {
        assertTrue(store.findByPushUsername(null).isEmpty());
    }

    @Test
    void findByPushUsername_unknownHandle_returnsEmpty() {
        assertTrue(store.findByPushUsername("nobody").isEmpty());
    }

    // ---- findAll ----

    @Test
    void findAll_returnsAllUsers() {
        var all = store.findAll();
        assertEquals(2, all.size());
    }

    // ---- pushUsernames preserved on returned entry ----

    @Test
    void returnedEntry_hasPushUsernames() {
        var result = store.findByUsername("alice");
        assertTrue(result.isPresent());
        assertTrue(result.get().getPushUsernames().contains("alice-corp"));
    }

    // ---- empty store ----

    @Test
    void emptyStore_allLookupsReturnEmpty() {
        StaticUserStore empty = new StaticUserStore(List.of());
        assertTrue(empty.findByUsername("anyone").isEmpty());
        assertTrue(empty.findByEmail("anyone@example.com").isEmpty());
        assertTrue(empty.findByPushUsername("anyone").isEmpty());
        assertEquals(0, empty.findAll().size());
    }
}
