package org.finos.gitproxy.user;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.finos.gitproxy.db.jdbc.DataSourceFactory;
import org.finos.gitproxy.db.jdbc.JdbcPushStore;
import org.finos.gitproxy.service.JdbcScmTokenCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CompositeUserStore}: config-first reads, JDBC-only writes, merged findAll.
 *
 * <p>Each test gets its own isolated H2 database to prevent state leakage.
 */
class CompositeUserStoreTest {

    CompositeUserStore store;
    JdbcUserStore jdbcStore;

    private static final UserEntry CONFIG_ALICE = UserEntry.builder()
            .username("alice")
            .passwordHash("{noop}config-pw")
            .emails(List.of("alice@config.com"))
            .scmIdentities(List.of(ScmIdentity.builder()
                    .provider("github")
                    .username("alice-config")
                    .build()))
            .roles(List.of("ADMIN"))
            .build();

    @BeforeEach
    void setUp() {
        DataSource ds = DataSourceFactory.h2InMemory("composite-test-" + UUID.randomUUID());
        JdbcPushStore pushStore = new JdbcPushStore(ds);
        pushStore.initialize();
        jdbcStore = new JdbcUserStore(ds, new JdbcScmTokenCache(ds, Duration.ofDays(1)));
        StaticUserStore configStore = new StaticUserStore(List.of(CONFIG_ALICE));
        store = new CompositeUserStore(configStore, jdbcStore);
    }

    // ── config-first reads ───────────────────────────────────────────────────────

    @Test
    void findByUsername_configUser_returnsConfigVersion() {
        // alice also exists in DB with different data — config must win
        jdbcStore.createUser("alice", "{noop}db-pw", "USER");
        var result = store.findByUsername("alice");
        assertTrue(result.isPresent());
        assertEquals("{noop}config-pw", result.get().getPasswordHash());
        assertEquals(List.of("ADMIN"), result.get().getRoles());
    }

    @Test
    void findByUsername_dbOnlyUser_returnsDbVersion() {
        jdbcStore.createUser("bob", "{noop}bob-pw", "USER");
        var result = store.findByUsername("bob");
        assertTrue(result.isPresent());
        assertEquals("{noop}bob-pw", result.get().getPasswordHash());
    }

    @Test
    void findByUsername_unknown_returnsEmpty() {
        assertTrue(store.findByUsername("nobody").isEmpty());
    }

    @Test
    void findByEmail_configUser_found() {
        assertTrue(store.findByEmail("alice@config.com").isPresent());
    }

    @Test
    void findByEmail_dbUser_found() {
        jdbcStore.createUser("bob", "{noop}pw", "USER");
        jdbcStore.addEmail("bob", "bob@example.com");
        assertTrue(store.findByEmail("bob@example.com").isPresent());
    }

    @Test
    void findByScmIdentity_configUser_found() {
        assertTrue(store.findByScmIdentity("github", "alice-config").isPresent());
    }

    @Test
    void findByScmIdentity_dbUser_found() {
        jdbcStore.createUser("bob", "{noop}pw", "USER");
        jdbcStore.addScmIdentity("bob", "github", "bob-gh");
        assertTrue(store.findByScmIdentity("github", "bob-gh").isPresent());
    }

    // ── findAll merges both ──────────────────────────────────────────────────────

    @Test
    void findAll_mergesBothStores_configTakesPrecedenceOnCollision() {
        jdbcStore.createUser("alice", "{noop}db-pw", "USER"); // collision with config alice
        jdbcStore.createUser("bob", "{noop}bob-pw", "USER");

        var all = store.findAll();
        assertEquals(2, all.size());
        var aliceResult = all.stream()
                .filter(u -> "alice".equals(u.getUsername()))
                .findFirst()
                .orElseThrow();
        // config alice wins
        assertEquals("{noop}config-pw", aliceResult.getPasswordHash());
    }

    // ── enriched queries ────────────────────────────────────────────────────────

    @Test
    void findEmailsWithVerified_configUser_returnsConfigEmails() {
        var emails = store.findEmailsWithVerified("alice");
        assertEquals(1, emails.size());
        assertEquals("alice@config.com", emails.get(0).get("email"));
        assertEquals("config", emails.get(0).get("source"));
    }

    @Test
    void findEmailsWithVerified_jdbcUser_returnsJdbcEmails() {
        jdbcStore.createUser("bob", "{noop}pw", "USER");
        jdbcStore.addEmail("bob", "bob@example.com");
        var emails = store.findEmailsWithVerified("bob");
        assertEquals(1, emails.size());
        assertEquals("bob@example.com", emails.get(0).get("email"));
    }

    @Test
    void findScmIdentitiesWithVerified_configUser_returnsConfigIdentities() {
        var ids = store.findScmIdentitiesWithVerified("alice");
        assertEquals(1, ids.size());
        assertEquals("github", ids.get(0).get("provider"));
        assertEquals("alice-config", ids.get(0).get("username"));
    }

    @Test
    void findScmIdentitiesWithVerified_jdbcUser_returnsJdbcIdentities() {
        jdbcStore.createUser("bob", "{noop}pw", "USER");
        jdbcStore.addScmIdentity("bob", "github", "bob-gh");
        var ids = store.findScmIdentitiesWithVerified("bob");
        assertEquals(1, ids.size());
        assertEquals("bob-gh", ids.get(0).get("username"));
    }

    // ── writes delegate to JDBC ──────────────────────────────────────────────────

    @Test
    void createUser_createsInJdbc() {
        store.createUser("charlie", "{noop}pw", "USER");
        assertTrue(jdbcStore.findByUsername("charlie").isPresent());
    }

    @Test
    void upsertUser_createsInJdbc() {
        store.upsertUser("oidcuser");
        assertTrue(jdbcStore.findByUsername("oidcuser").isPresent());
    }

    @Test
    void addEmail_addsToJdbc() {
        jdbcStore.createUser("bob", "{noop}pw", "USER");
        store.addEmail("bob", "bob@example.com");
        assertTrue(jdbcStore.findByEmail("bob@example.com").isPresent());
    }

    @Test
    void deleteUser_removesFromJdbc() {
        jdbcStore.createUser("bob", "{noop}pw", "USER");
        store.deleteUser("bob");
        assertTrue(jdbcStore.findByUsername("bob").isEmpty());
    }
}
