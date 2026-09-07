package com.rbc.fogwall.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.rbc.fogwall.dashboard.service.ScmSshKeyImporter.OAuthSshKeyEntry;
import com.rbc.fogwall.provider.GitHubProvider;
import com.rbc.fogwall.ssh.SshKeyUtils;
import com.rbc.fogwall.user.SshKeyEntry;
import com.rbc.fogwall.user.UserEntry;
import com.rbc.fogwall.user.UserStore;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ScmSshKeyImporterTest {

    private static final String SAMPLE_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIIQiTzhWg82OVGUGpUMctA7FoBSZteJQ5R/TPaVfCC95";

    private static UserEntry userWith(SshKeyEntry... keys) {
        return UserEntry.builder().username("alice").sshKeys(List.of(keys)).build();
    }

    private static SshKeyEntry importedKey(String fingerprint, String... sources) {
        return SshKeyEntry.builder()
                .username("alice")
                .fingerprint(fingerprint)
                .locked(true)
                .authSource(String.join(", ", sources))
                .authSources(List.of(sources))
                .build();
    }

    // ── Import ────────────────────────────────────────────────────────────────

    @Test
    void newKey_addsLockedKeyWithTitleAsLabel() {
        UserStore mutable = mock(UserStore.class);
        String fingerprint = SshKeyUtils.fingerprint(SAMPLE_KEY);

        ScmSshKeyImporter.importAll(
                mutable, userWith(), "github", Optional.of(List.of(new OAuthSshKeyEntry(SAMPLE_KEY, "work laptop"))));

        verify(mutable).addSshKey("alice", fingerprint, SAMPLE_KEY, "work laptop", true, "github");
    }

    @Test
    void blankTitle_fallsBackToDefaultLabel() {
        UserStore mutable = mock(UserStore.class);

        ScmSshKeyImporter.importAll(
                mutable, userWith(), "github", Optional.of(List.of(new OAuthSshKeyEntry(SAMPLE_KEY, ""))));

        verify(mutable)
                .addSshKey(eq("alice"), any(), eq(SAMPLE_KEY), eq("Imported from github"), eq(true), eq("github"));
    }

    @Test
    void alreadyRegisteredFingerprint_stillCallsAddSshKey_soASecondProviderIsRecordedAsASource() {
        // A key can legitimately be verified by more than one linked provider. addSshKey itself decides whether
        // that is a no-op or an additional source, so this must never pre-filter fingerprints the user has.
        UserStore mutable = mock(UserStore.class);
        String fingerprint = SshKeyUtils.fingerprint(SAMPLE_KEY);

        ScmSshKeyImporter.importAll(
                mutable,
                userWith(importedKey(fingerprint, "github")),
                "gitlab",
                Optional.of(List.of(new OAuthSshKeyEntry(SAMPLE_KEY, "work laptop"))));

        verify(mutable).addSshKey("alice", fingerprint, SAMPLE_KEY, "work laptop", true, "gitlab");
    }

    @Test
    void keyAlreadyVouchedForByThisProvider_isNotCountedAsAdded() {
        UserStore mutable = mock(UserStore.class);
        String fingerprint = SshKeyUtils.fingerprint(SAMPLE_KEY);

        var result = ScmSshKeyImporter.importAll(
                mutable,
                userWith(importedKey(fingerprint, "github")),
                "github",
                Optional.of(List.of(new OAuthSshKeyEntry(SAMPLE_KEY, "work laptop"))));

        assertEquals(0, result.added());
    }

    // ── A failed read is not an empty key set ─────────────────────────────────

    @Test
    void failedFetch_importsNothing() {
        UserStore mutable = mock(UserStore.class);

        var result = ScmSshKeyImporter.importAll(mutable, userWith(), "github", Optional.empty());

        verify(mutable, never()).addSshKey(any(), any(), any(), any(), eq(true), any());
        assertTrue(result.fetchFailed());
        assertEquals(0, result.added());
    }

    @Test
    void fetch_withoutAToken_readsNothing() {
        // Every provider is read through its authenticated /user/keys endpoint, so no token means no read.
        var provider = GitHubProvider.builder().name("github").build();

        assertTrue(ScmSshKeyImporter.fetch(provider, null).isEmpty());
        assertTrue(ScmSshKeyImporter.fetch(provider, "  ").isEmpty());
    }
}
