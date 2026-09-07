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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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

    /** What an import added, and whether the provider could be read at all. */
    public record ImportResult(int added, boolean fetchFailed) {
        public static ImportResult failed() {
            return new ImportResult(0, true);
        }
    }

    private ScmSshKeyImporter() {}

    /**
     * Reads the SSH keys registered on the account whose token this is, from each provider's authenticated
     * {@code /user/keys} endpoint.
     *
     * <p>Authenticated on every provider, deliberately. The alternative on GitLab and Forgejo is a public listing keyed
     * by username, which answers a weaker question: if an account is deleted and its username re-registered, that
     * endpoint would report a different person's keys, and fogwall would store them as proven. The token names the
     * account itself, so there is nothing to confuse. Every caller has one — linking has just obtained it, and the
     * refresh only runs for identities that were OAuth-verified.
     *
     * <p>The scopes already requested cover it: GitLab's {@code read_user} and Forgejo's {@code read:user} both grant
     * the authenticated user endpoints, and the GitHub App holds "Git SSH keys: Read-only".
     *
     * @return the keys, or empty when there is no token or the provider could not be read — never an empty list to mean
     *     failure
     */
    public static Optional<List<OAuthSshKeyEntry>> fetch(FogwallProvider provider, String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            log.debug("No token for '{}' — cannot read its SSH keys, so nothing may be withdrawn", provider.getName());
            return Optional.empty();
        }
        if (provider instanceof GitHubProvider github) {
            return get(github.getApiUrl() + "/user/keys", "token " + accessToken, "GitHub");
        } else if (provider instanceof GitLabProvider gitlab) {
            return get(gitlab.getApiUrl() + "/user/keys", "Bearer " + accessToken, "GitLab");
        } else if (provider instanceof ForgejoProvider forgejo) {
            return get(forgejo.getApiUrl() + "/user/keys", "Bearer " + accessToken, "Forgejo");
        }
        // A provider with no key listing is not a failure — there is simply nothing to import from it.
        return Optional.of(List.of());
    }

    private static Optional<List<OAuthSshKeyEntry>> get(String url, String authorization, String providerLabel) {
        try {
            String responseBody = Request.get(url)
                    .addHeader("Authorization", authorization)
                    .execute(FogwallHttpExecutor.instance())
                    .returnContent()
                    .asString();
            return Optional.of(List.of(new JsonMapper().readValue(responseBody, OAuthSshKeyEntry[].class)));
        } catch (Exception e) {
            log.warn("Failed to read SSH keys from {}: {}", providerLabel, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Registers every key the provider reports. Additive: a key fogwall already holds is re-offered to
     * {@link UserStore#addSshKey}, which records an additional source when a second provider also vouches for it.
     *
     * @param upstream the provider's keys, or empty when the read failed — a failed read imports nothing
     */
    public static ImportResult importAll(
            UserStore mutable, UserEntry user, String providerId, Optional<List<OAuthSshKeyEntry>> upstream) {
        if (upstream.isEmpty()) {
            log.warn("Could not read SSH keys for user '{}' on provider '{}'", user.getUsername(), providerId);
            return ImportResult.failed();
        }
        Set<String> alreadyVouched = user.getSshKeys().stream()
                .filter(k -> k.isVouchedForBy(providerId))
                .map(SshKeyEntry::getFingerprint)
                .collect(Collectors.toSet());
        int added = 0;
        for (OAuthSshKeyEntry key : upstream.get()) {
            String fingerprint = importKey(mutable, user.getUsername(), providerId, key);
            if (fingerprint != null && !alreadyVouched.contains(fingerprint)) {
                added++;
            }
        }
        return new ImportResult(added, false);
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
}
