package org.finos.gitproxy.user;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.finos.gitproxy.db.jdbc.DataSourceFactory; // used in setUp and upsertAll_secondCall_preservesExistingPassword
import org.finos.gitproxy.db.jdbc.JdbcPushStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link JdbcUserStore} backed by an H2 in-memory database.
 *
 * <p>Each test gets its own isolated H2 database to prevent state leakage. The schema is initialized via
 * {@link JdbcPushStore#initialize()} (same schema.sql that includes the user tables).
 */
class JdbcUserStoreIntegrationTest {

    JdbcUserStore store;

    @BeforeEach
    void setUp() {
        DataSource ds = DataSourceFactory.h2InMemory("user-test-" + UUID.randomUUID());
        // schema.sql includes the user tables — initialize via JdbcPushStore which owns the schema resource
        JdbcPushStore pushStore = new JdbcPushStore(ds);
        pushStore.initialize();
        store = new JdbcUserStore(ds);
    }

    private static UserEntry user(String username, List<String> emails, List<String> pushUsernames) {
        return UserEntry.builder()
                .username(username)
                .passwordHash("{noop}pw")
                .emails(emails)
                .pushUsernames(pushUsernames)
                .scmIdentities(List.of())
                .build();
    }

    // ---- basic upsert / findByUsername ----

    @Test
    void upsertAll_insertsUser_findByUsernameReturns() {
        store.upsertAll(List.of(user("alice", List.of("alice@example.com"), List.of("alice"))));

        var result = store.findByUsername("alice");
        assertTrue(result.isPresent());
        assertEquals("alice", result.get().getUsername());
        assertEquals("{noop}pw", result.get().getPasswordHash());
    }

    @Test
    void findByUsername_unknown_returnsEmpty() {
        assertTrue(store.findByUsername("nobody").isEmpty());
    }

    // ---- upsert preserves password on second call ----

    @Test
    void upsertAll_secondCall_preservesExistingPassword() {
        // First seed with one password
        store.upsertAll(List.of(user("alice", List.of("alice@example.com"), List.of())));

        // Second call with a different password hash — should not overwrite (check-then-insert)
        UserEntry aliceNewPw = UserEntry.builder()
                .username("alice")
                .passwordHash("{bcrypt}$2a$12$different-hash")
                .emails(List.of("alice@example.com"))
                .pushUsernames(List.of())
                .scmIdentities(List.of())
                .build();
        store.upsertAll(List.of(aliceNewPw));

        var result = store.findByUsername("alice");
        assertTrue(result.isPresent());
        // First-write wins: original password must be preserved
        assertEquals("{noop}pw", result.get().getPasswordHash());
    }

    // ---- emails ----

    @Test
    void upsertAll_insertsEmails_findByEmailReturns() {
        store.upsertAll(List.of(user("alice", List.of("alice@example.com", "alice@corp.com"), List.of())));

        assertTrue(store.findByEmail("alice@example.com").isPresent());
        assertTrue(store.findByEmail("alice@corp.com").isPresent());
    }

    @Test
    void findByEmail_caseInsensitive() {
        store.upsertAll(List.of(user("alice", List.of("alice@example.com"), List.of())));

        assertTrue(store.findByEmail("ALICE@EXAMPLE.COM").isPresent());
    }

    @Test
    void findByEmail_unknown_returnsEmpty() {
        assertTrue(store.findByEmail("nobody@example.com").isEmpty());
    }

    @Test
    void findByEmail_null_returnsEmpty() {
        assertTrue(store.findByEmail(null).isEmpty());
    }

    @Test
    void upsertAll_emailsReplaced_onSecondCall() {
        store.upsertAll(List.of(user("alice", List.of("old@example.com"), List.of())));
        store.upsertAll(List.of(user("alice", List.of("new@example.com"), List.of())));

        assertFalse(store.findByEmail("old@example.com").isPresent(), "Old email must be replaced");
        assertTrue(store.findByEmail("new@example.com").isPresent(), "New email must be present");
    }

    // ---- push usernames ----

    @Test
    void upsertAll_insertsPushUsernames_findByPushUsernameReturns() {
        store.upsertAll(List.of(user("alice", List.of(), List.of("corp-alice", "alice-github"))));

        var result = store.findByPushUsername("corp-alice");
        assertTrue(result.isPresent());
        assertEquals("alice", result.get().getUsername());

        assertTrue(store.findByPushUsername("alice-github").isPresent());
    }

    @Test
    void findByPushUsername_notRegistered_returnsEmpty() {
        store.upsertAll(List.of(user("alice", List.of(), List.of("alice"))));

        assertTrue(store.findByPushUsername("bob").isEmpty());
    }

    @Test
    void findByPushUsername_null_returnsEmpty() {
        assertTrue(store.findByPushUsername(null).isEmpty());
    }

    @Test
    void upsertAll_pushUsernamesReplaced_onSecondCall() {
        store.upsertAll(List.of(user("alice", List.of(), List.of("old-handle"))));
        store.upsertAll(List.of(user("alice", List.of(), List.of("new-handle"))));

        assertFalse(store.findByPushUsername("old-handle").isPresent(), "Old push username must be replaced");
        assertTrue(store.findByPushUsername("new-handle").isPresent(), "New push username must be present");
    }

    // ---- returned entry hydrates pushUsernames ----

    @Test
    void findByUsername_returnedEntry_hasPushUsernames() {
        store.upsertAll(List.of(user("alice", List.of(), List.of("corp-alice", "alice-github"))));

        var result = store.findByUsername("alice");
        assertTrue(result.isPresent());
        var pushUsernames = result.get().getPushUsernames();
        assertTrue(pushUsernames.contains("corp-alice"));
        assertTrue(pushUsernames.contains("alice-github"));
    }

    // ---- findAll ----

    @Test
    void findAll_returnsAllSeededUsers() {
        store.upsertAll(List.of(user("alice", List.of(), List.of()), user("bob", List.of(), List.of())));

        var all = store.findAll();
        assertEquals(2, all.size());
    }

    @Test
    void findAll_emptyStore_returnsEmpty() {
        assertEquals(0, store.findAll().size());
    }

    // ---- multiple users, correct routing ----

    @Test
    void multipleUsers_pushUsernamesRoutedCorrectly() {
        store.upsertAll(List.of(
                user("alice", List.of(), List.of("alice-github")), user("bob", List.of(), List.of("bob-github"))));

        assertEquals("alice", store.findByPushUsername("alice-github").get().getUsername());
        assertEquals("bob", store.findByPushUsername("bob-github").get().getUsername());
        assertTrue(store.findByPushUsername("charlie-github").isEmpty());
    }
}
