package org.finos.gitproxy.user;

import static org.junit.jupiter.api.Assertions.*;

import com.mongodb.client.MongoClients;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Tag("integration")
class MongoUserStoreIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

    MongoUserStore store;

    @BeforeEach
    void setUp() {
        store = new MongoUserStore(
                MongoClients.create(MONGO.getConnectionString()),
                "testdb_" + UUID.randomUUID().toString().replace("-", ""));
        store.initialize();
    }

    // ── basic CRUD ──────────────────────────────────────────────────────────────

    @Test
    void createUser_andFindByUsername_roundTripsRoles() {
        store.createUser("alice", "{noop}secret", "USER,ADMIN");

        UserEntry found = store.findByUsername("alice").orElseThrow();
        assertEquals("alice", found.getUsername());
        assertEquals("{noop}secret", found.getPasswordHash());
        assertTrue(found.getRoles().contains("USER"));
        assertTrue(found.getRoles().contains("ADMIN"));
    }

    @Test
    void createUser_duplicateUsername_throws() {
        store.createUser("bob", null, "USER");
        assertThrows(IllegalArgumentException.class, () -> store.createUser("bob", null, "USER"));
    }

    @Test
    void deleteUser_removesUser() {
        store.createUser("alice", null, "USER");
        store.deleteUser("alice");
        assertTrue(store.findByUsername("alice").isEmpty());
    }

    @Test
    void deleteUser_unknownUser_throws() {
        assertThrows(IllegalArgumentException.class, () -> store.deleteUser("nobody"));
    }

    @Test
    void setPassword_updatesHash() {
        store.createUser("alice", "{noop}old", "USER");
        store.setPassword("alice", "{noop}new");
        assertEquals("{noop}new", store.findByUsername("alice").orElseThrow().getPasswordHash());
    }

    @Test
    void setPassword_unknownUser_throws() {
        assertThrows(IllegalArgumentException.class, () -> store.setPassword("nobody", "hash"));
    }

    @Test
    void upsertUser_createsIfAbsent_andIsNoopIfPresent() {
        store.upsertUser("idp-user");
        assertTrue(store.findByUsername("idp-user").isPresent());

        // Second call must not throw
        assertDoesNotThrow(() -> store.upsertUser("idp-user"));
    }

    @Test
    void findAll_sortedByUsername() {
        store.createUser("charlie", null, "USER");
        store.createUser("alice", null, "USER");
        store.createUser("bob", null, "USER");

        List<UserEntry> all = store.findAll();
        assertEquals(
                List.of("alice", "bob", "charlie"),
                all.stream().map(UserEntry::getUsername).toList());
    }

    // ── email management ────────────────────────────────────────────────────────

    @Test
    void addEmail_andFindByEmail_caseInsensitive() {
        store.createUser("alice", null, "USER");
        store.addEmail("alice", "Alice@Example.COM");

        assertTrue(store.findByEmail("alice@example.com").isPresent());
        assertTrue(store.findByEmail("ALICE@EXAMPLE.COM").isPresent());
    }

    @Test
    void removeEmail_removesAddedEmail() {
        store.createUser("alice", null, "USER");
        store.addEmail("alice", "alice@example.com");
        store.removeEmail("alice", "alice@example.com");

        assertTrue(store.findByEmail("alice@example.com").isEmpty());
    }

    @Test
    void removeEmail_lockedEmail_throws() {
        store.createUser("alice", null, "USER");
        store.upsertLockedEmail("alice", "alice@idp.com", "oidc");

        assertThrows(LockedEmailException.class, () -> store.removeEmail("alice", "alice@idp.com"));
    }

    @Test
    void findEmailsWithVerified_reflectsLockedAndSource() {
        store.createUser("alice", null, "USER");
        store.addEmail("alice", "alice@example.com");
        store.upsertLockedEmail("alice", "alice@idp.com", "oidc");

        List<Map<String, Object>> emails = store.findEmailsWithVerified("alice");
        Map<String, Object> local = emails.stream()
                .filter(e -> "alice@example.com".equals(e.get("email")))
                .findFirst()
                .orElseThrow();
        assertFalse((Boolean) local.get("locked"));

        Map<String, Object> locked = emails.stream()
                .filter(e -> "alice@idp.com".equals(e.get("email")))
                .findFirst()
                .orElseThrow();
        assertTrue((Boolean) locked.get("locked"));
        assertTrue((Boolean) locked.get("verified"));
        assertEquals("oidc", locked.get("source"));
    }

    // ── SCM identity management ─────────────────────────────────────────────────

    @Test
    void addScmIdentity_andFindByScmIdentity() {
        store.createUser("alice", null, "USER");
        store.addScmIdentity("alice", "github", "alice-gh");

        UserEntry found = store.findByScmIdentity("github", "alice-gh").orElseThrow();
        assertEquals("alice", found.getUsername());
    }

    @Test
    void addScmIdentity_sameUserSameIdentity_isNoop() {
        store.createUser("alice", null, "USER");
        store.addScmIdentity("alice", "github", "alice-gh");
        assertDoesNotThrow(() -> store.addScmIdentity("alice", "github", "alice-gh"));
    }

    @Test
    void addScmIdentity_differentUserSameIdentity_throws() {
        store.createUser("alice", null, "USER");
        store.createUser("bob", null, "USER");
        store.addScmIdentity("alice", "github", "shared-handle");
        assertThrows(ScmIdentityConflictException.class, () -> store.addScmIdentity("bob", "github", "shared-handle"));
    }

    @Test
    void removeScmIdentity_removesIdentity() {
        store.createUser("alice", null, "USER");
        store.addScmIdentity("alice", "github", "alice-gh");
        store.removeScmIdentity("alice", "github", "alice-gh");
        assertTrue(store.findByScmIdentity("github", "alice-gh").isEmpty());
    }

    @Test
    void findScmIdentitiesWithVerified_returnsCorrectShape() {
        store.createUser("alice", null, "USER");
        store.addScmIdentity("alice", "github", "alice-gh");

        List<Map<String, Object>> identities = store.findScmIdentitiesWithVerified("alice");
        assertEquals(1, identities.size());
        assertEquals("github", identities.get(0).get("provider"));
        assertEquals("alice-gh", identities.get(0).get("username"));
    }
}
