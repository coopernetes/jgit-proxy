package com.rbc.fogwall.user;

import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Value;

/** An SSH public key registered by a proxy user for SSH git transport authentication. */
@Value
@Builder
public class SshKeyEntry {
    String id;
    String username;

    /** SHA-256 fingerprint in OpenSSH format ({@code SHA256:...}). Used as the lookup key at auth time. */
    String fingerprint;

    /** Full public key body — algorithm + base64, no comment (e.g. {@code ssh-ed25519 AAAA...}). */
    String publicKey;

    /** Optional human-readable label (e.g. "work laptop"). */
    String label;

    Instant createdAt;

    /**
     * True when this key was declared in the config file, or imported via SCM OAuth (#40), and cannot be removed via
     * the dashboard.
     */
    @Builder.Default
    boolean locked = false;

    /**
     * Display label for where this key came from: {@code "config"}, a provider id, or a comma-joined list when more
     * than one linked provider vouches for the key.
     *
     * <p>Not safe to compare against a provider id — use {@link #authSources} for that. A key two providers both report
     * reads {@code "github, gitlab"} here, which equals neither.
     */
    @Builder.Default
    String authSource = "config";

    /**
     * Every source that vouches for this key, one entry per provider (or the single value {@code "config"}). This is
     * the field to test when deciding whether a given provider proved a key, since a key can legitimately be verified
     * by more than one linked account.
     */
    @Builder.Default
    List<String> authSources = List.of();

    /**
     * How this key's provenance reads for a person: {@code "config"}, a provider id, or the sources joined when more
     * than one vouches for it. Derived rather than stored, so the stores do not each have to render it.
     */
    public String sourceLabel() {
        return authSources.isEmpty() ? authSource : String.join(", ", authSources);
    }

    /** Whether {@code providerId} is among the sources that vouch for this key. */
    public boolean isVouchedForBy(String providerId) {
        if (providerId == null) {
            return false;
        }
        if (!authSources.isEmpty()) {
            return authSources.stream().anyMatch(providerId::equalsIgnoreCase);
        }
        // Older rows, and config-declared keys, carry only the single-valued label.
        return providerId.equalsIgnoreCase(authSource);
    }
}
