package com.rbc.fogwall.dashboard.service;

import com.rbc.fogwall.crypto.TokenCipherProvider;
import com.rbc.fogwall.dashboard.service.ScmSshKeyImporter.ReconcileResult;
import com.rbc.fogwall.provider.FogwallProvider;
import com.rbc.fogwall.provider.ProviderRegistry;
import com.rbc.fogwall.service.SshScmIdentityEnricher;
import com.rbc.fogwall.user.ReadOnlyUserStore;
import com.rbc.fogwall.user.ScmIdentity;
import com.rbc.fogwall.user.ScmOAuthTokenStore;
import com.rbc.fogwall.user.UserEntry;
import com.rbc.fogwall.user.UserStore;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Re-reads SSH keys from each linked provider and reconciles fogwall's imported copy.
 *
 * <p>Import otherwise happens once, when the account is linked, so a key the user removes upstream stays usable in
 * fogwall forever. That matters most in {@code scm-oauth.identity-mode: strict}, where an imported key is the whole
 * basis for resolving an SSH push: without this sweep, revoking a key upstream has no effect here.
 *
 * <p>Every reconcile is per user and per provider, and a provider that cannot be read leaves that pair untouched. One
 * unreachable provider therefore cannot revoke keys for anyone.
 */
@Slf4j
@RequiredArgsConstructor
public class SshKeyRefreshService {

    /** What one sweep did, for the log line an operator reads afterwards. */
    public record SweepSummary(int usersExamined, int usersChanged, int keysAdded, int keysWithdrawn, int failures) {}

    private final ReadOnlyUserStore userStore;
    private final ProviderRegistry providerRegistry;
    private final Optional<ScmOAuthTokenStore> tokenStore;
    private final TokenCipherProvider tokenCipherProvider;
    private final SshScmIdentityEnricher sshEnricher;

    /** Refreshes every user holding a verified identity. Safe to call concurrently with pushes. */
    public SweepSummary refreshAll() {
        List<UserEntry> users = userStore.findAll();
        int examined = 0;
        int changed = 0;
        int added = 0;
        int withdrawn = 0;
        int failures = 0;
        for (UserEntry user : users) {
            if (!hasVerifiedIdentity(user)) {
                continue;
            }
            examined++;
            SweepSummary one = refresh(user);
            added += one.keysAdded();
            withdrawn += one.keysWithdrawn();
            failures += one.failures();
            if (one.keysAdded() > 0 || one.keysWithdrawn() > 0) {
                changed++;
            }
        }
        SweepSummary summary = new SweepSummary(examined, changed, added, withdrawn, failures);
        log.info(
                "SSH key refresh finished: {} user(s) examined, {} changed, {} key(s) added, {} withdrawn, {} provider"
                        + " read failure(s)",
                summary.usersExamined(),
                summary.usersChanged(),
                summary.keysAdded(),
                summary.keysWithdrawn(),
                summary.failures());
        return summary;
    }

    private SweepSummary refresh(UserEntry user) {
        if (!(userStore instanceof UserStore mutable)) {
            log.debug("User store is read-only — SSH key refresh skipped");
            return new SweepSummary(0, 0, 0, 0, 0);
        }
        int added = 0;
        int withdrawn = 0;
        int failures = 0;
        for (ScmIdentity identity : verifiedIdentities(user)) {
            Optional<FogwallProvider> provider = providerRegistry.getProvider(identity.getProvider());
            if (provider.isEmpty()) {
                log.debug(
                        "Identity for user '{}' names provider '{}', which is not configured — skipping",
                        user.getUsername(),
                        identity.getProvider());
                continue;
            }
            ReconcileResult result = ScmSshKeyImporter.reconcile(
                    mutable,
                    user,
                    identity.getProvider(),
                    ScmSshKeyImporter.fetch(provider.get(), accessToken(user, identity.getProvider())));
            added += result.added();
            withdrawn += result.withdrawn();
            if (result.fetchFailed()) {
                failures++;
            }
            if (result.changedAnything() && sshEnricher != null) {
                // The permissive-mode cache keyed on this identity is now stale.
                sshEnricher.evict(identity.getProvider(), identity.getUsername());
            }
        }
        return new SweepSummary(1, added > 0 || withdrawn > 0 ? 1 : 0, added, withdrawn, failures);
    }

    /**
     * Returns the stored OAuth token for this pair, or {@code null} when there is none — the user has not linked that
     * provider, or the stored token could not be decrypted. Without one the provider is not read and nothing is
     * withdrawn for it.
     */
    private String accessToken(UserEntry user, String providerId) {
        if (tokenStore.isEmpty()) {
            return null;
        }
        Optional<byte[]> encrypted = tokenStore.get().findAccessToken(user.getUsername(), providerId);
        if (encrypted.isEmpty()) {
            return null;
        }
        var cipher = tokenCipherProvider.cipher();
        if (cipher.isEmpty()) {
            log.warn(
                    "Token encryption key unavailable — cannot use the stored '{}' token for user '{}'",
                    providerId,
                    user.getUsername());
            return null;
        }
        try {
            return new String(cipher.get().decrypt(encrypted.get()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn(
                    "Could not decrypt the stored '{}' token for user '{}': {}",
                    providerId,
                    user.getUsername(),
                    e.getMessage());
            return null;
        }
    }

    private static List<ScmIdentity> verifiedIdentities(UserEntry user) {
        if (user.getScmIdentities() == null) {
            return List.of();
        }
        return user.getScmIdentities().stream().filter(ScmIdentity::isVerified).toList();
    }

    private static boolean hasVerifiedIdentity(UserEntry user) {
        return !verifiedIdentities(user).isEmpty();
    }
}
