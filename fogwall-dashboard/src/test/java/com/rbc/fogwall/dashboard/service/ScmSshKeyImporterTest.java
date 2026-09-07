package com.rbc.fogwall.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.rbc.fogwall.dashboard.service.ScmSshKeyImporter.OAuthSshKeyEntry;
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
    /** A fingerprint with no matching key body — withdrawal compares fingerprints and never parses a key. */
    private static final String GONE_FINGERPRINT = "SHA256:0000000000000000000000000000000000000000000";

    private static UserEntry userWith(SshKeyEntry... keys) {
        return UserEntry.builder().username("alice").sshKeys(List.of(keys)).build();
    }

    private static SshKeyEntry importedKey(String fingerprint, String authSource) {
        return SshKeyEntry.builder()
                .username("alice")
                .fingerprint(fingerprint)
                .locked(true)
                .authSource(authSource)
                .build();
    }

    // ── Import ────────────────────────────────────────────────────────────────

    @Test
    void newKey_addsLockedKeyWithTitleAsLabel() {
        UserStore mutable = mock(UserStore.class);
        String fingerprint = SshKeyUtils.fingerprint(SAMPLE_KEY);

        ScmSshKeyImporter.reconcile(
                mutable, userWith(), "github", Optional.of(List.of(new OAuthSshKeyEntry(SAMPLE_KEY, "work laptop"))));

        verify(mutable).addSshKey("alice", fingerprint, SAMPLE_KEY, "work laptop", true, "github");
    }

    @Test
    void blankTitle_fallsBackToDefaultLabel() {
        UserStore mutable = mock(UserStore.class);

        ScmSshKeyImporter.reconcile(
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

        ScmSshKeyImporter.reconcile(
                mutable,
                userWith(importedKey(fingerprint, "github")),
                "gitlab",
                Optional.of(List.of(new OAuthSshKeyEntry(SAMPLE_KEY, "work laptop"))));

        verify(mutable).addSshKey("alice", fingerprint, SAMPLE_KEY, "work laptop", true, "gitlab");
    }

    // ── Withdrawal ────────────────────────────────────────────────────────────

    @Test
    void keyNoLongerUpstream_isWithdrawn() {
        UserStore mutable = mock(UserStore.class);
        String gone = GONE_FINGERPRINT;

        var result = ScmSshKeyImporter.reconcile(
                mutable,
                userWith(importedKey(gone, "github")),
                "github",
                Optional.of(List.of(new OAuthSshKeyEntry(SAMPLE_KEY, "still here"))));

        verify(mutable).removeSshKeySource("alice", gone, "github");
        assertEquals(1, result.withdrawn());
    }

    @Test
    void keyFromAnotherProvider_isNotWithdrawn() {
        // Reconciling github must not touch what gitlab imported — each provider owns only its own claims.
        UserStore mutable = mock(UserStore.class);
        String gitlabKey = GONE_FINGERPRINT;

        var result = ScmSshKeyImporter.reconcile(
                mutable, userWith(importedKey(gitlabKey, "gitlab")), "github", Optional.of(List.of()));

        verify(mutable, never()).removeSshKeySource(any(), any(), any());
        assertEquals(0, result.withdrawn());
    }

    @Test
    void handAddedKey_isNotWithdrawn() {
        UserStore mutable = mock(UserStore.class);
        SshKeyEntry handAdded = SshKeyEntry.builder()
                .username("alice")
                .fingerprint(GONE_FINGERPRINT)
                .locked(false)
                .authSource("config")
                .build();

        ScmSshKeyImporter.reconcile(mutable, userWith(handAdded), "github", Optional.of(List.of()));

        verify(mutable, never()).removeSshKeySource(any(), any(), any());
    }

    @Test
    void emptyUpstream_withdrawsEveryImportedKey() {
        // An account that genuinely has no keys left is a real state, distinct from a failed read.
        UserStore mutable = mock(UserStore.class);
        String fingerprint = SshKeyUtils.fingerprint(SAMPLE_KEY);

        var result = ScmSshKeyImporter.reconcile(
                mutable, userWith(importedKey(fingerprint, "github")), "github", Optional.of(List.of()));

        verify(mutable).removeSshKeySource("alice", fingerprint, "github");
        assertEquals(1, result.withdrawn());
        assertFalse(result.fetchFailed());
    }

    // ── A failed read is not an empty key set ─────────────────────────────────

    @Test
    void failedFetch_withdrawsNothing() {
        // The security-relevant case: a provider outage or revoked token must not look like "all keys removed"
        // and lock the user out of SSH.
        UserStore mutable = mock(UserStore.class);
        String fingerprint = SshKeyUtils.fingerprint(SAMPLE_KEY);

        var result = ScmSshKeyImporter.reconcile(
                mutable, userWith(importedKey(fingerprint, "github")), "github", Optional.empty());

        verify(mutable, never()).removeSshKeySource(any(), any(), any());
        verify(mutable, never()).addSshKey(any(), any(), any(), any(), eq(true), any());
        assertTrue(result.fetchFailed());
        assertEquals(0, result.withdrawn());
        assertEquals(0, result.added());
    }
}
