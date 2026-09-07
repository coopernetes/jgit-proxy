package com.rbc.fogwall.dashboard.service;

import com.rbc.fogwall.net.FogwallHttpExecutor;
import com.rbc.fogwall.provider.FogwallProvider;
import com.rbc.fogwall.provider.ForgejoProvider;
import com.rbc.fogwall.provider.GitHubProvider;
import com.rbc.fogwall.provider.GitLabProvider;
import com.rbc.fogwall.ssh.SshKeyUtils;
import com.rbc.fogwall.user.SshKeyConflictException;
import com.rbc.fogwall.user.SshKeyEntry;
import com.rbc.fogwall.user.UserEntry;
import com.rbc.fogwall.user.UserStore;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.fluent.Request;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a user's SSH public keys from the provider they linked, and reconciles fogwall's imported copy against them.
 *
 * <p>Used at link time, where the imported set starts empty, and by the periodic refresh, where it already exists and
 * may have drifted — a key the user removed upstream is still trusted by fogwall until something notices.
 *
 * <h2>A failed fetch is not an empty key set</h2>
 *
 * <p>{@link #fetch} returns an empty {@link Optional} when the provider could not be read and a present-but-empty list
 * when the account genuinely has no keys. Collapsing the two would make a provider outage or a revoked token look like
 * "this user removed all their keys", and {@link #reconcile} would then withdraw every key it had — locking users out
 * of SSH on a transient failure. Only a successful read is allowed to remove anything.
 */
@Slf4j
public final class ScmSshKeyImporter {

    /** One key as the provider reports it. Field names match all three providers' JSON. */
    public record OAuthSshKeyEntry(String key, String title) {}

    /** What a reconcile changed, so a key disappearing is explainable afterwards. */
    public record ReconcileResult(int added, int withdrawn, boolean fetchFailed) {
        public static ReconcileResult failed() {
            return new ReconcileResult(0, 0, true);
        }

        public boolean changedAnything() {
            return added > 0 || withdrawn > 0;
        }
    }

    private ScmSshKeyImporter() {}

    /**
     * Reads the keys {@code scmUsername} has registered on {@code provider}.
     *
     * @return the keys, or empty when the provider could not be read — never an empty list to mean failure
     */
    public static Optional<List<OAuthSshKeyEntry>> fetch(
            FogwallProvider provider, String scmUsername, String accessToken) {
        if (provider instanceof GitHubProvider github) {
            if (accessToken == null) {
                // GitHub's listing is the authenticated one, deliberately, so the App permission stays visible on the
                // consent screen. With no token there is nothing to read and nothing may be withdrawn.
                log.debug("No stored token for GitHub — skipping SSH key read");
                return Optional.empty();
            }
            return get(github.getApiUrl() + "/user/keys", "token " + accessToken, "GitHub");
        } else if (provider instanceof GitLabProvider gitlab) {
            // Public per-username listing; the read_user scope does not gate SSH keys either way.
            return get(gitlab.getApiUrl() + "/users/" + encode(scmUsername) + "/keys", null, "GitLab");
        } else if (provider instanceof ForgejoProvider forgejo) {
            // Public too; the bearer header only avoids the shared unauthenticated rate limit some instances apply.
            return get(
                    forgejo.getApiUrl() + "/users/" + encode(scmUsername) + "/keys",
                    accessToken != null ? "Bearer " + accessToken : null,
                    "Forgejo");
        }
        // A provider with no key listing is not a failure — there is simply nothing to import from it.
        return Optional.of(List.of());
    }

    private static Optional<List<OAuthSshKeyEntry>> get(String url, String authorization, String providerLabel) {
        try {
            Request request = Request.get(url);
            if (authorization != null) {
                request.addHeader("Authorization", authorization);
            }
            String responseBody = request.execute(FogwallHttpExecutor.instance())
                    .returnContent()
                    .asString();
            return Optional.of(List.of(new JsonMapper().readValue(responseBody, OAuthSshKeyEntry[].class)));
        } catch (Exception e) {
            log.warn("Failed to read SSH keys from {}: {}", providerLabel, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Adds keys the provider reports and withdraws this provider's claim on imported keys it no longer reports.
     *
     * <p>Withdrawal is per key rather than "clear the source and re-import", so a push arriving mid-reconcile never
     * sees an empty key set. A key another linked provider still claims, or one that came from config, survives the
     * withdrawal — that decision belongs to the store.
     *
     * @param upstream the provider's keys, or empty when the read failed; a failed read withdraws nothing
     */
    public static ReconcileResult reconcile(
            UserStore mutable, UserEntry user, String providerId, Optional<List<OAuthSshKeyEntry>> upstream) {
        if (upstream.isEmpty()) {
            log.warn(
                    "Keeping the imported SSH keys for user '{}' on provider '{}': the provider could not be read, so"
                            + " no key can be shown to have been withdrawn",
                    user.getUsername(),
                    providerId);
            return ReconcileResult.failed();
        }
        Set<String> upstreamFingerprints = new LinkedHashSet<>();
        int added = 0;
        for (OAuthSshKeyEntry key : upstream.get()) {
            String fingerprint = importKey(mutable, user.getUsername(), providerId, key);
            if (fingerprint != null) {
                upstreamFingerprints.add(fingerprint);
                added++;
            }
        }
        int withdrawn = withdrawMissing(mutable, user, providerId, upstreamFingerprints);
        return new ReconcileResult(added, withdrawn, false);
    }

    /**
     * Registers one key and returns its fingerprint, or {@code null} if it could not be used.
     *
     * <p>Deliberately does not pre-filter keys the user already has: {@link UserStore#addSshKey} records an additional
     * source for a key a second provider also reports, and pre-filtering would skip that path.
     */
    private static String importKey(UserStore mutable, String username, String providerId, OAuthSshKeyEntry key) {
        try {
            String normalised = SshKeyUtils.normalise(key.key());
            String fingerprint = SshKeyUtils.fingerprint(normalised);
            String label = key.title() != null && !key.title().isBlank() ? key.title() : "Imported from " + providerId;
            mutable.addSshKey(username, fingerprint, normalised, label, true, providerId);
            return fingerprint;
        } catch (SshKeyConflictException e) {
            log.warn(
                    "Skipping SSH key from '{}' for user '{}': fingerprint already registered to a different proxy"
                            + " user ('{}') — an admin must remove it from that account first",
                    providerId,
                    username,
                    e.getOwner());
            return null;
        } catch (Exception e) {
            log.warn("Skipping unparsable SSH key from '{}' for user '{}': {}", providerId, username, e.getMessage());
            return null;
        }
    }

    /** Withdraws this provider's claim on every imported key the provider no longer reports. */
    private static int withdrawMissing(
            UserStore mutable, UserEntry user, String providerId, Set<String> upstreamFingerprints) {
        List<SshKeyEntry> imported = new ArrayList<>();
        for (SshKeyEntry key : user.getSshKeys() != null ? user.getSshKeys() : List.<SshKeyEntry>of()) {
            if (key.isLocked()
                    && providerId.equalsIgnoreCase(key.getAuthSource())
                    && !upstreamFingerprints.contains(key.getFingerprint())) {
                imported.add(key);
            }
        }
        for (SshKeyEntry key : imported) {
            mutable.removeSshKeySource(user.getUsername(), key.getFingerprint(), providerId);
            log.info(
                    "Withdrew '{}' as a source for SSH key {} of user '{}' — no longer registered on that provider",
                    providerId,
                    key.getFingerprint(),
                    user.getUsername());
        }
        return imported.size();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
