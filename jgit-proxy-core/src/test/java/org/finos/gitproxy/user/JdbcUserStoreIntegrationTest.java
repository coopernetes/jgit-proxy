package org.finos.gitproxy.user;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.finos.gitproxy.db.jdbc.DataSourceFactory;
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
        JdbcPushStore pushStore = new JdbcPushStore(ds);
        pushStore.initialize();
        store = new JdbcUserStore(ds);
    }

    private static UserEntry user(String username, List<String> emails, List<ScmIdentity> scmIdentities) {
        return UserEntry.builder()
                .username(username)
                .passwordHash("{noop}pw")
                .emails(emails)
                .scmIdentities(scmIdentities)
                .build();
    }

    private static ScmIdentity scm(String provider, String login) {
        return ScmIdentity.builder().provider(provider).username(login).build();
    }

    // ---- basic upsert / findByUsername ----

    @Test
    void upsertAll_insertsUser_findByUsernameReturns() {
        store.upsertAll(List.of(user("alice", List.of("alice@example.com"), List.of())));

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
        store.upsertAll(List.of(user("alice", List.of("alice@example.com"), List.of())));

        UserEntry aliceNewPw = UserEntry.builder()
                .username("alice")
                .passwordHash("{bcrypt}$2a$12$different-hash")
                .emails(List.of("alice@example.com"))
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

    // ---- scm identities ----

    @Test
    void upsertAll_insertsScmIdentities_findByScmIdentityReturns() {
        store.upsertAll(
                List.of(user("alice", List.of(), List.of(scm("github", "alice-gh"), scm("gitlab", "alice-gl")))));

        var result = store.findByScmIdentity("github", "alice-gh");
        assertTrue(result.isPresent());
        assertEquals("alice", result.get().getUsername());

        assertTrue(store.findByScmIdentity("gitlab", "alice-gl").isPresent());
    }

    @Test
    void findByScmIdentity_wrongProvider_returnsEmpty() {
        store.upsertAll(List.of(user("alice", List.of(), List.of(scm("github", "alice-gh")))));

        assertTrue(store.findByScmIdentity("gitlab", "alice-gh").isEmpty());
    }

    @Test
    void findByScmIdentity_notRegistered_returnsEmpty() {
        store.upsertAll(List.of(user("alice", List.of(), List.of(scm("github", "alice-gh")))));

        assertTrue(store.findByScmIdentity("github", "bob-gh").isEmpty());
    }

    @Test
    void findByScmIdentity_null_returnsEmpty() {
        assertTrue(store.findByScmIdentity(null, "alice-gh").isEmpty());
        assertTrue(store.findByScmIdentity("github", null).isEmpty());
    }

    @Test
    void upsertAll_scmIdentitiesReplaced_onSecondCall() {
        store.upsertAll(List.of(user("alice", List.of(), List.of(scm("github", "old-handle")))));
        store.upsertAll(List.of(user("alice", List.of(), List.of(scm("github", "new-handle")))));

        assertFalse(store.findByScmIdentity("github", "old-handle").isPresent(), "Old SCM identity must be replaced");
        assertTrue(store.findByScmIdentity("github", "new-handle").isPresent(), "New SCM identity must be present");
    }

    // ---- returned entry hydrates scmIdentities ----

    @Test
    void findByUsername_returnedEntry_hasScmIdentities() {
        store.upsertAll(
                List.of(user("alice", List.of(), List.of(scm("github", "alice-gh"), scm("gitlab", "alice-gl")))));

        var result = store.findByUsername("alice");
        assertTrue(result.isPresent());
        var ids = result.get().getScmIdentities();
        assertTrue(
                ids.stream().anyMatch(id -> "github".equals(id.getProvider()) && "alice-gh".equals(id.getUsername())));
        assertTrue(
                ids.stream().anyMatch(id -> "gitlab".equals(id.getProvider()) && "alice-gl".equals(id.getUsername())));
    }

    // ---- multiple users, correct routing ----

    @Test
    void multipleUsers_scmIdentitiesRoutedCorrectly() {
        store.upsertAll(List.of(
                user("alice", List.of(), List.of(scm("github", "alice-gh"))),
                user("bob", List.of(), List.of(scm("github", "bob-gh")))));

        assertEquals(
                "alice", store.findByScmIdentity("github", "alice-gh").get().getUsername());
        assertEquals("bob", store.findByScmIdentity("github", "bob-gh").get().getUsername());
        assertTrue(store.findByScmIdentity("github", "charlie-gh").isEmpty());
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
}
