package com.rbc.fogwall.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rbc.fogwall.user.ScmIdentity;
import com.rbc.fogwall.user.SshKeyEntry;
import com.rbc.fogwall.user.UserEntry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ImportedKeyIdentityResolverTest {

    private static final String FP = "SHA256:abc123";

    private static SshKeyEntry key(String fingerprint, boolean locked, String authSource) {
        return SshKeyEntry.builder()
                .username("alice")
                .fingerprint(fingerprint)
                .locked(locked)
                .authSource(authSource)
                .build();
    }

    private static ScmIdentity identity(String provider, String username, boolean verified) {
        return ScmIdentity.builder()
                .provider(provider)
                .username(username)
                .verified(verified)
                .build();
    }

    private static UserEntry user(List<SshKeyEntry> keys, List<ScmIdentity> identities) {
        return UserEntry.builder()
                .username("alice")
                .sshKeys(keys)
                .scmIdentities(identities)
                .build();
    }

    @Test
    void importedKeyAndVerifiedIdentity_resolves() {
        UserEntry u = user(List.of(key(FP, true, "github")), List.of(identity("github", "alice-gh", true)));
        assertEquals(Optional.of("alice-gh"), ImportedKeyIdentityResolver.resolve(u, "github", FP));
    }

    @Test
    void handAddedKey_doesNotResolve() {
        // The whole point of strict mode: a key typed into the profile page proves nothing about the SCM account,
        // even when the provider would confirm it is registered there.
        UserEntry u = user(List.of(key(FP, false, "config")), List.of(identity("github", "alice-gh", true)));
        assertTrue(ImportedKeyIdentityResolver.resolve(u, "github", FP).isEmpty());
    }

    @Test
    void configDeclaredKey_doesNotResolve() {
        UserEntry u = user(List.of(key(FP, true, "config")), List.of(identity("github", "alice-gh", true)));
        assertTrue(ImportedKeyIdentityResolver.resolve(u, "github", FP).isEmpty());
    }

    @Test
    void keyImportedFromAnotherProvider_doesNotResolveForThisOne() {
        UserEntry u = user(List.of(key(FP, true, "gitlab")), List.of(identity("github", "alice-gh", true)));
        assertTrue(ImportedKeyIdentityResolver.resolve(u, "github", FP).isEmpty());
    }

    @Test
    void unverifiedIdentity_doesNotResolve() {
        UserEntry u = user(List.of(key(FP, true, "github")), List.of(identity("github", "alice-gh", false)));
        assertTrue(ImportedKeyIdentityResolver.resolve(u, "github", FP).isEmpty());
    }

    @Test
    void differentFingerprint_doesNotResolve() {
        UserEntry u = user(List.of(key(FP, true, "github")), List.of(identity("github", "alice-gh", true)));
        assertTrue(ImportedKeyIdentityResolver.resolve(u, "github", "SHA256:different")
                .isEmpty());
    }

    @Test
    void verifiedIdentityForAnotherProvider_doesNotResolve() {
        UserEntry u = user(List.of(key(FP, true, "github")), List.of(identity("gitlab", "alice-gl", true)));
        assertTrue(ImportedKeyIdentityResolver.resolve(u, "github", FP).isEmpty());
    }

    @Test
    void nullInputsAndEmptyCollections_resolveToEmpty() {
        assertTrue(ImportedKeyIdentityResolver.resolve(null, "github", FP).isEmpty());
        assertTrue(ImportedKeyIdentityResolver.resolve(user(List.of(), List.of()), "github", FP)
                .isEmpty());
        assertTrue(ImportedKeyIdentityResolver.resolve(user(List.of(key(FP, true, "github")), null), "github", FP)
                .isEmpty());
        UserEntry linked = user(List.of(key(FP, true, "github")), List.of(identity("github", "alice-gh", true)));
        assertTrue(ImportedKeyIdentityResolver.resolve(linked, "github", null).isEmpty());
        assertTrue(ImportedKeyIdentityResolver.resolve(linked, null, FP).isEmpty());
    }
}
