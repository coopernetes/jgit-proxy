package com.rbc.fogwall.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.rbc.fogwall.db.jdbc.DataSourceFactory;
import com.rbc.fogwall.db.jdbc.JdbcPushStore;
import com.rbc.fogwall.permission.JdbcRepoPermissionStore;
import com.rbc.fogwall.permission.RepoPermission;
import com.rbc.fogwall.service.JdbcScmTokenCache;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
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
    JdbcRepoPermissionStore permissionStore;

    @BeforeEach
    void setUp() {
        DataSource ds = DataSourceFactory.h2InMemory("user-test-" + UUID.randomUUID());
        JdbcPushStore pushStore = new JdbcPushStore(ds);
        pushStore.initialize();
        store = new JdbcUserStore(ds, new JdbcScmTokenCache(ds, Duration.ofDays(1)));
        permissionStore = new JdbcRepoPermissionStore(ds);
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

    // ---- findEmailsWithVerified includes locked + source ----

    @Test
    void findEmailsWithVerified_localEmail_lockedFalseSourceLocal() {
        store.upsertAll(List.of(user("alice", List.of("alice@example.com"), List.of())));

        var emails = store.findEmailsWithVerified("alice");
        assertEquals(1, emails.size());
        assertEquals("alice@example.com", emails.get(0).get("email"));
        assertEquals(false, emails.get(0).get("locked"));
        assertEquals("local", emails.get(0).get("source"));
    }

    // ---- upsertUser auto-provisioning ----

    @Test
    void upsertUser_createsUserWithNullPassword() {
        store.upsertUser("ldapuser");

        var result = store.findByUsername("ldapuser");
        assertTrue(result.isPresent());
        assertNull(result.get().getPasswordHash());
    }

    @Test
    void upsertUser_idempotent_secondCallNoOp() {
        store.upsertUser("ldapuser");
        store.upsertUser("ldapuser"); // must not throw or duplicate

        assertEquals(1, store.findAll().size());
    }

    @Test
    void upsertUser_preservesExistingUser() {
        store.upsertAll(List.of(user("alice", List.of(), List.of())));
        store.upsertUser("alice"); // should not overwrite existing row

        var result = store.findByUsername("alice");
        assertTrue(result.isPresent());
        assertEquals("{noop}pw", result.get().getPasswordHash());
    }

    @Test
    void upsertUser_withRoles_setsRolesOnNewUser() {
        store.upsertUser("oidcuser", List.of("USER", "ADMIN"));

        var result = store.findByUsername("oidcuser");
        assertTrue(result.isPresent());
        assertNull(result.get().getPasswordHash());
        assertTrue(result.get().getRoles().contains("ADMIN"));
        assertTrue(result.get().getRoles().contains("USER"));
    }

    @Test
    void upsertUser_withRoles_syncsRolesOnSubsequentLogin() {
        store.upsertUser("oidcuser", List.of("USER"));
        store.upsertUser("oidcuser", List.of("USER", "SELF_CERTIFY"));

        var result = store.findByUsername("oidcuser");
        assertTrue(result.isPresent());
        assertTrue(result.get().getRoles().contains("SELF_CERTIFY"));
    }

    @Test
    void upsertUser_withRoles_syncsRolesForYamlUser() {
        store.upsertAll(List.of(user("alice", List.of(), List.of())));
        store.upsertUser("alice", List.of("USER", "ADMIN"));

        var result = store.findByUsername("alice");
        assertTrue(result.isPresent());
        assertEquals("{noop}pw", result.get().getPasswordHash());
        assertTrue(result.get().getRoles().contains("ADMIN"));
    }

    // ---- upsertLockedEmail ----

    @Test
    void upsertLockedEmail_insertsLockedEmail() {
        store.upsertUser("oidcuser");
        store.upsertLockedEmail("oidcuser", "oidcuser@corp.com", "oidc");

        var emails = store.findEmailsWithVerified("oidcuser");
        assertEquals(1, emails.size());
        assertEquals("oidcuser@corp.com", emails.get(0).get("email"));
        assertEquals(true, emails.get(0).get("locked"));
        assertEquals("oidc", emails.get(0).get("source"));
    }

    @Test
    void upsertLockedEmail_idempotent_updatesExistingRow() {
        store.upsertUser("ldapuser");
        store.upsertLockedEmail("ldapuser", "ldapuser@corp.com", "ldap");
        store.upsertLockedEmail("ldapuser", "ldapuser@corp.com", "ldap"); // second call must not throw

        var emails = store.findEmailsWithVerified("ldapuser");
        assertEquals(1, emails.size());
        assertEquals(true, emails.get(0).get("locked"));
    }

    @Test
    void upsertLockedEmail_locksExistingUnlockedEmail() {
        store.upsertAll(List.of(user("alice", List.of("alice@corp.com"), List.of())));
        // Email exists as unlocked (local); IdP login should lock it
        store.upsertLockedEmail("alice", "alice@corp.com", "oidc");

        var emails = store.findEmailsWithVerified("alice");
        assertEquals(1, emails.size());
        assertEquals(true, emails.get(0).get("locked"));
        assertEquals("oidc", emails.get(0).get("source"));
    }

    // ---- removeEmail rejects locked emails ----

    @Test
    void removeEmail_lockedEmail_throwsLockedEmailException() {
        store.upsertUser("oidcuser");
        store.upsertLockedEmail("oidcuser", "oidcuser@corp.com", "oidc");

        assertThrows(LockedEmailException.class, () -> store.removeEmail("oidcuser", "oidcuser@corp.com"));
    }

    @Test
    void removeEmail_unlockedEmail_removesSuccessfully() {
        store.upsertAll(List.of(user("alice", List.of("alice@example.com"), List.of())));
        store.removeEmail("alice", "alice@example.com");

        assertTrue(store.findByEmail("alice@example.com").isEmpty());
    }

    // ---- addScmIdentity uniqueness ----

    @Test
    void addScmIdentity_sameUser_isIdempotent() {
        store.upsertAll(List.of(user("alice", List.of(), List.of())));
        store.addScmIdentity("alice", "github", "alice-gh");
        store.addScmIdentity("alice", "github", "alice-gh"); // must not throw

        assertEquals(
                "alice", store.findByScmIdentity("github", "alice-gh").get().getUsername());
    }

    @Test
    void addScmIdentity_differentUser_throwsConflict() {
        store.upsertAll(List.of(user("alice", List.of(), List.of()), user("bob", List.of(), List.of())));
        store.addScmIdentity("alice", "github", "shared-handle");

        var ex = assertThrows(
                ScmIdentityConflictException.class, () -> store.addScmIdentity("bob", "github", "shared-handle"));
        assertEquals("alice", ex.getOwner());
    }

    // ---- upsertVerifiedScmIdentity (#40) ----

    @Test
    void addScmIdentity_defaultsToUnverified() {
        store.upsertAll(List.of(user("alice", List.of(), List.of())));
        store.addScmIdentity("alice", "github", "alice-gh");

        ScmIdentity identity =
                store.findByUsername("alice").get().getScmIdentities().get(0);
        assertFalse(identity.isVerified());
    }

    @Test
    void upsertVerifiedScmIdentity_setsVerifiedTrue() {
        store.upsertAll(List.of(user("alice", List.of(), List.of())));
        store.upsertVerifiedScmIdentity("alice", "github", "alice-gh");

        ScmIdentity identity =
                store.findByUsername("alice").get().getScmIdentities().get(0);
        assertEquals("alice-gh", identity.getUsername());
        assertTrue(identity.isVerified());
    }

    @Test
    void upsertVerifiedScmIdentity_replacesPriorIdentityForSameProvider() {
        store.upsertAll(List.of(user("alice", List.of(), List.of())));
        store.addScmIdentity("alice", "github", "old-manual-handle");
        store.upsertVerifiedScmIdentity("alice", "github", "new-oauth-handle");

        List<ScmIdentity> identities = store.findByUsername("alice").get().getScmIdentities();
        assertEquals(1, identities.size());
        assertEquals("new-oauth-handle", identities.get(0).getUsername());
        assertTrue(identities.get(0).isVerified());
    }

    @Test
    void upsertVerifiedScmIdentity_reLinkingSameIdentityStaysVerified() {
        store.upsertAll(List.of(user("alice", List.of(), List.of())));
        store.upsertVerifiedScmIdentity("alice", "github", "alice-gh");
        store.upsertVerifiedScmIdentity("alice", "github", "alice-gh"); // re-link, e.g. token refresh flow

        List<ScmIdentity> identities = store.findByUsername("alice").get().getScmIdentities();
        assertEquals(1, identities.size());
        assertTrue(identities.get(0).isVerified());
    }

    @Test
    void upsertVerifiedScmIdentity_differentUser_throwsConflict() {
        store.upsertAll(List.of(user("alice", List.of(), List.of()), user("bob", List.of(), List.of())));
        store.upsertVerifiedScmIdentity("alice", "github", "shared-handle");

        var ex = assertThrows(
                ScmIdentityConflictException.class,
                () -> store.upsertVerifiedScmIdentity("bob", "github", "shared-handle"));
        assertEquals("alice", ex.getOwner());
    }

    // ---- SSH key locked flag (#40 OAuth import) ----

    private static final String SAMPLE_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIIQiTzhWg82OVGUGpUMctA7FoBSZteJQ5R/TPaVfCC95";

    @Test
    void addSshKey_defaultsToUnlocked() {
        store.upsertAll(List.of(user("alice", List.of(), List.of())));
        store.addSshKey("alice", "SHA256:abc", SAMPLE_KEY, "laptop");

        assertFalse(store.findSshKeys("alice").get(0).isLocked());
    }

    @Test
    void addSshKey_explicitLockedTrue_persistsLocked() {
        store.upsertAll(List.of(user("alice", List.of(), List.of())));
        store.addSshKey("alice", "SHA256:abc", SAMPLE_KEY, "Imported from GitHub", true);

        assertTrue(store.findSshKeys("alice").get(0).isLocked());
    }

    @Test
    void removeSshKey_unlocked_succeeds() {
        store.upsertAll(List.of(user("alice", List.of(), List.of())));
        SshKeyEntry key = store.addSshKey("alice", "SHA256:abc", SAMPLE_KEY, "laptop", false);

        store.removeSshKey("alice", key.getId());

        assertTrue(store.findSshKeys("alice").isEmpty());
    }

    @Test
    void removeSshKey_locked_throwsAndDoesNotRemove() {
        store.upsertAll(List.of(user("alice", List.of(), List.of())));
        SshKeyEntry key = store.addSshKey("alice", "SHA256:abc", SAMPLE_KEY, "Imported from GitHub", true);

        assertThrows(LockedSshKeyException.class, () -> store.removeSshKey("alice", key.getId()));
        assertEquals(1, store.findSshKeys("alice").size());
    }

    // ---- removeVerifiedScmIdentity (#40) ----

    @Test
    void removeVerifiedScmIdentity_removesOnlyVerifiedOne() {
        store.upsertAll(List.of(user("alice", List.of(), List.of())));
        store.upsertVerifiedScmIdentity("alice", "github", "alice-gh");

        store.removeVerifiedScmIdentity("alice", "github");

        assertTrue(store.findByUsername("alice").get().getScmIdentities().isEmpty());
    }

    @Test
    void removeVerifiedScmIdentity_leavesUnverifiedIdentityUntouched() {
        store.upsertAll(List.of(user("alice", List.of(), List.of())));
        store.addScmIdentity("alice", "gitlab", "alice-gl"); // manual entry, unverified

        store.removeVerifiedScmIdentity("alice", "gitlab");

        List<ScmIdentity> identities = store.findByUsername("alice").get().getScmIdentities();
        assertEquals(1, identities.size());
        assertEquals("alice-gl", identities.get(0).getUsername());
    }

    // ---- multi-source SSH keys (#40: same key verified by more than one linked provider) ----

    @Test
    void addSshKey_sameKeySecondProvider_recordsBothSources() {
        store.upsertAll(List.of(user("alice", List.of(), List.of())));
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
        store.upsertAll(List.of(user("alice", List.of(), List.of())));
        store.addSshKey("alice", "SHA256:abc", SAMPLE_KEY, "GitHub key", true, "github");

        store.removeSshKeysByAuthSource("alice", "github");

        assertTrue(store.findSshKeys("alice").isEmpty());
    }

    @Test
    void removeSshKeysByAuthSource_otherSourceRemains_keepsKeyRelabeled() {
        store.upsertAll(List.of(user("alice", List.of(), List.of())));
        store.addSshKey("alice", "SHA256:abc", SAMPLE_KEY, "GitHub key", true, "github");
        store.addSshKey("alice", "SHA256:abc", SAMPLE_KEY, "GitHub key", true, "gitlab");

        store.removeSshKeysByAuthSource("alice", "github");

        List<SshKeyEntry> keys = store.findSshKeys("alice");
        assertEquals(1, keys.size());
        assertEquals("gitlab", keys.get(0).getAuthSource());
    }

    @Test
    void removeSshKeysByAuthSource_configLockedKey_untouched() {
        store.upsertAll(List.of(user("alice", List.of(), List.of())));
        store.addSshKey("alice", "SHA256:abc", SAMPLE_KEY, "laptop", true, "config");

        store.removeSshKeysByAuthSource("alice", "github");

        assertEquals(1, store.findSshKeys("alice").size());
    }

    // ---- multi-source emails (#40: same email verified by more than one linked provider) ----

    @Test
    void upsertLockedEmail_sameEmailSecondProvider_recordsBothSources() {
        store.upsertAll(List.of(user("alice", List.of(), List.of())));
        store.upsertLockedEmail("alice", "alice@example.com", "github");
        store.upsertLockedEmail("alice", "alice@example.com", "gitlab");

        Map<String, Object> entry = store.findEmailsWithVerified("alice").get(0);
        assertTrue(((String) entry.get("source")).contains("github"));
        assertTrue(((String) entry.get("source")).contains("gitlab"));
    }

    @Test
    void removeEmailsByAuthSource_lastSourceRemoved_deletesEmail() {
        store.upsertAll(List.of(user("alice", List.of(), List.of())));
        store.upsertLockedEmail("alice", "alice@example.com", "github");

        store.removeEmailsByAuthSource("alice", "github");

        assertTrue(store.findEmailsWithVerified("alice").isEmpty());
    }

    @Test
    void removeEmailsByAuthSource_otherSourceRemains_keepsEmailRelabeled() {
        store.upsertAll(List.of(user("alice", List.of(), List.of())));
        store.upsertLockedEmail("alice", "alice@example.com", "github");
        store.upsertLockedEmail("alice", "alice@example.com", "gitlab");

        store.removeEmailsByAuthSource("alice", "github");

        Map<String, Object> entry = store.findEmailsWithVerified("alice").get(0);
        assertEquals("gitlab", entry.get("source"));
    }

    @Test
    void removeEmailsByAuthSource_localEmail_untouched() {
        store.upsertAll(List.of(user("alice", List.of("alice@example.com"), List.of())));

        store.removeEmailsByAuthSource("alice", "github");

        assertEquals(1, store.findEmailsWithVerified("alice").size());
    }

    // ---- deleteUser cascades to repo_permissions ----

    @Test
    void deleteUser_cascadesPermissions() {
        store.upsertAll(List.of(user("alice", List.of(), List.of()), user("bob", List.of(), List.of())));
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
