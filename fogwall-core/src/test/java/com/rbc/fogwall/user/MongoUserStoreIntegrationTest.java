package com.rbc.fogwall.user;

import static org.junit.jupiter.api.Assertions.*;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.rbc.fogwall.permission.MongoRepoPermissionStore;
import com.rbc.fogwall.permission.RepoPermission;
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
    static final MongoDBContainer MONGO =
            new MongoDBContainer(DockerImageName.parse("docker.io/mongo:7.0").asCompatibleSubstituteFor("mongo"));

    MongoUserStore store;
    MongoRepoPermissionStore permissionStore;

    @BeforeEach
    void setUp() {
        MongoClient client = MongoClients.create(MONGO.getConnectionString());
        String dbName = "testdb_" + UUID.randomUUID().toString().replace("-", "");
        store = new MongoUserStore(client, dbName);
        store.initialize();
        permissionStore = new MongoRepoPermissionStore(client, dbName);
        permissionStore.initialize();
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

    // ── verified SCM identities (#40) ──────────────────────────────────────────

    @Test
    void upsertVerifiedScmIdentity_setsVerifiedTrue() {
        store.createUser("alice", null, "USER");
        store.upsertVerifiedScmIdentity("alice", "github", "alice-gh");

        UserEntry found = store.findByUsername("alice").orElseThrow();
        assertEquals(1, found.getScmIdentities().size());
        assertTrue(found.getScmIdentities().get(0).isVerified());
    }

    @Test
    void upsertVerifiedScmIdentity_replacesPriorIdentityForSameProvider() {
        store.createUser("alice", null, "USER");
        store.addScmIdentity("alice", "github", "old-manual-handle");
        store.upsertVerifiedScmIdentity("alice", "github", "new-oauth-handle");

        List<ScmIdentity> identities =
                store.findByUsername("alice").orElseThrow().getScmIdentities();
        assertEquals(1, identities.size());
        assertEquals("new-oauth-handle", identities.get(0).getUsername());
        assertTrue(identities.get(0).isVerified());
    }

    @Test
    void upsertVerifiedScmIdentity_differentUser_throwsConflict() {
        store.createUser("alice", null, "USER");
        store.createUser("bob", null, "USER");
        store.upsertVerifiedScmIdentity("alice", "github", "shared-handle");

        assertThrows(
                ScmIdentityConflictException.class,
                () -> store.upsertVerifiedScmIdentity("bob", "github", "shared-handle"));
    }

    @Test
    void removeVerifiedScmIdentity_removesOnlyVerifiedOne() {
        store.createUser("alice", null, "USER");
        store.upsertVerifiedScmIdentity("alice", "github", "alice-gh");

        store.removeVerifiedScmIdentity("alice", "github");

        assertTrue(
                store.findByUsername("alice").orElseThrow().getScmIdentities().isEmpty());
    }

    @Test
    void removeVerifiedScmIdentity_leavesUnverifiedIdentityUntouched() {
        store.createUser("alice", null, "USER");
        store.addScmIdentity("alice", "gitlab", "alice-gl"); // manual entry, unverified

        store.removeVerifiedScmIdentity("alice", "gitlab");

        List<ScmIdentity> identities =
                store.findByUsername("alice").orElseThrow().getScmIdentities();
        assertEquals(1, identities.size());
        assertEquals("alice-gl", identities.get(0).getUsername());
    }

    @Test
    void removeScmIdentity_verifiedIdentity_throws() {
        store.createUser("alice", null, "USER");
        store.upsertVerifiedScmIdentity("alice", "github", "alice-gh");

        assertThrows(VerifiedScmIdentityException.class, () -> store.removeScmIdentity("alice", "github", "alice-gh"));
    }

    // ── SSH key management (#40) ────────────────────────────────────────────────

    private static final String SAMPLE_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIIQiTzhWg82OVGUGpUMctA7FoBSZteJQ5R/TPaVfCC95";

    @Test
    void addSshKey_andFindBySshFingerprint() {
        store.createUser("alice", null, "USER");
        store.addSshKey("alice", "SHA256:abc", SAMPLE_KEY, "laptop", false, "config");

        UserEntry found = store.findBySshFingerprint("SHA256:abc").orElseThrow();
        assertEquals("alice", found.getUsername());
        assertEquals(1, store.findSshKeys("alice").size());
        assertFalse(store.findSshKeys("alice").get(0).isLocked());
    }

    @Test
    void addSshKey_differentUserSameFingerprint_throws() {
        store.createUser("alice", null, "USER");
        store.createUser("bob", null, "USER");
        store.addSshKey("alice", "SHA256:abc", SAMPLE_KEY, "laptop", false, "config");

        assertThrows(
                SshKeyConflictException.class,
                () -> store.addSshKey("bob", "SHA256:abc", SAMPLE_KEY, "laptop", false, "config"));
    }

    @Test
    void addSshKey_sameKeySecondProvider_recordsBothSources() {
        store.createUser("alice", null, "USER");
        store.addSshKey("alice", "SHA256:abc", SAMPLE_KEY, "GitHub key", true, "github");
        store.addSshKey("alice", "SHA256:abc", SAMPLE_KEY, "GitHub key", true, "gitlab");

        List<SshKeyEntry> keys = store.findSshKeys("alice");
        assertEquals(1, keys.size());
        // Both sources are structured now; the joined label is derived for display.
        assertTrue(keys.get(0).isVouchedForBy("github"));
        assertTrue(keys.get(0).isVouchedForBy("gitlab"));
        assertTrue(keys.get(0).sourceLabel().contains("github"));
        assertTrue(keys.get(0).sourceLabel().contains("gitlab"));
    }

    @Test
    void removeSshKeysByAuthSource_lastSourceRemoved_deletesKey() {
        store.createUser("alice", null, "USER");
        store.addSshKey("alice", "SHA256:abc", SAMPLE_KEY, "GitHub key", true, "github");

        store.removeSshKeysByAuthSource("alice", "github");

        assertTrue(store.findSshKeys("alice").isEmpty());
    }

    @Test
    void removeSshKeysByAuthSource_otherSourceRemains_keepsKeyRelabeled() {
        store.createUser("alice", null, "USER");
        store.addSshKey("alice", "SHA256:abc", SAMPLE_KEY, "GitHub key", true, "github");
        store.addSshKey("alice", "SHA256:abc", SAMPLE_KEY, "GitHub key", true, "gitlab");

        store.removeSshKeysByAuthSource("alice", "github");

        List<SshKeyEntry> keys = store.findSshKeys("alice");
        assertEquals(1, keys.size());
        assertEquals("gitlab", keys.get(0).getAuthSource());
    }

    @Test
    void removeSshKeysByAuthSource_configLockedKey_untouched() {
        store.createUser("alice", null, "USER");
        store.addSshKey("alice", "SHA256:abc", SAMPLE_KEY, "laptop", true, "config");

        store.removeSshKeysByAuthSource("alice", "github");

        assertEquals(1, store.findSshKeys("alice").size());
    }

    @Test
    void removeSshKey_unlocked_succeeds() {
        store.createUser("alice", null, "USER");
        SshKeyEntry key = store.addSshKey("alice", "SHA256:abc", SAMPLE_KEY, "laptop", false, "config");

        store.removeSshKey("alice", key.getId());

        assertTrue(store.findSshKeys("alice").isEmpty());
    }

    @Test
    void removeSshKey_locked_throwsAndDoesNotRemove() {
        store.createUser("alice", null, "USER");
        SshKeyEntry key = store.addSshKey("alice", "SHA256:abc", SAMPLE_KEY, "Imported from GitHub", true, "github");

        assertThrows(LockedSshKeyException.class, () -> store.removeSshKey("alice", key.getId()));
        assertEquals(1, store.findSshKeys("alice").size());
    }

    // ── multi-source emails (#40) ───────────────────────────────────────────────

    @Test
    void upsertLockedEmail_sameEmailSecondProvider_recordsBothSources() {
        store.createUser("alice", null, "USER");
        store.upsertLockedEmail("alice", "alice@example.com", "github");
        store.upsertLockedEmail("alice", "alice@example.com", "gitlab");

        Map<String, Object> entry = store.findEmailsWithVerified("alice").get(0);
        assertTrue(((String) entry.get("source")).contains("github"));
        assertTrue(((String) entry.get("source")).contains("gitlab"));
    }

    @Test
    void removeEmailsByAuthSource_lastSourceRemoved_deletesEmail() {
        store.createUser("alice", null, "USER");
        store.upsertLockedEmail("alice", "alice@example.com", "github");

        store.removeEmailsByAuthSource("alice", "github");

        assertTrue(store.findEmailsWithVerified("alice").isEmpty());
    }

    @Test
    void removeEmailsByAuthSource_otherSourceRemains_keepsEmailRelabeled() {
        store.createUser("alice", null, "USER");
        store.upsertLockedEmail("alice", "alice@example.com", "github");
        store.upsertLockedEmail("alice", "alice@example.com", "gitlab");

        store.removeEmailsByAuthSource("alice", "github");

        Map<String, Object> entry = store.findEmailsWithVerified("alice").get(0);
        assertEquals("gitlab", entry.get("source"));
    }

    @Test
    void removeEmailsByAuthSource_localEmail_untouched() {
        store.createUser("alice", null, "USER");
        store.addEmail("alice", "alice@example.com");

        store.removeEmailsByAuthSource("alice", "github");

        assertEquals(1, store.findEmailsWithVerified("alice").size());
    }

    // ── deleteUser cascades to repo_permissions ─────────────────────────────────

    @Test
    void deleteUser_cascadesPermissions() {
        store.createUser("alice", null, "USER");
        store.createUser("bob", null, "USER");
        permissionStore.save(RepoPermission.builder()
                .username("alice")
                .provider("github")
                .value("org/repo")
                .build());
        permissionStore.save(RepoPermission.builder()
                .username("bob")
                .provider("github")
                .value("org/repo")
                .build());

        store.deleteUser("alice");

        assertTrue(permissionStore.findByUsername("alice").isEmpty());
        assertEquals(1, permissionStore.findByUsername("bob").size());
    }
}
