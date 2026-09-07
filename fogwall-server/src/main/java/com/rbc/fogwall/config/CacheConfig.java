package com.rbc.fogwall.config;

import java.time.Duration;
import lombok.Data;

/**
 * Binds the {@code cache:} block in fogwall.yml — the local-mirror clone depth for each proxy mode (#476).
 *
 * <p>fogwall keeps a local bare mirror of each upstream repo to inspect push content. How much history that mirror
 * holds is a genuine per-deployment tradeoff, not something that should differ by accident: a full mirror is the most
 * correct basis for reachability checks but a first clone of a large repo can exceed HTTP timeouts, while a shallow
 * mirror clones cheaply but truncates history. Each mode has its own sub-block ({@code cache.proxy} and
 * {@code cache.server}) and only the <b>defaults</b> differ — server mode defaults to full, transparent proxy to
 * shallow. Both knobs are available to both modes: shallow cloning is fully supported for server mode too (it serves
 * large repos as well), it is simply not the default.
 *
 * <p>Two knobs, {@code shallow-since} preferred:
 *
 * <ul>
 *   <li>{@code shallow-since} — a duration such as {@code 90d}, {@code 12h}, {@code 30m} (or ISO-8601 {@code PT…}). The
 *       mirror keeps history back to that point in time. An operator can reason about "keep 90 days" and write it
 *       against a retention requirement, which a raw commit count cannot express (depth is a graph-distance bound, not
 *       a function of age, so which commits fall outside {@code depth=100} is unpredictable for a given repo).
 *   <li>{@code clone-depth} — an integer commit depth ({@code 0} = full history). Used only when {@code shallow-since}
 *       is unset.
 * </ul>
 *
 * <p>Reachability checks that would get a wrong answer from a truncated mirror deepen it on demand
 * ({@code LocalRepositoryCache.refreshNow} unshallows), so a shallow default is a cost optimisation, not a correctness
 * ceiling.
 */
@Data
public class CacheConfig {

    /** Transparent-proxy mirror. Defaults to shallow ({@code clone-depth: 100}) — see {@link TransportCacheConfig}. */
    private TransportCacheConfig proxy = new TransportCacheConfig();

    /** Server-mode mirror. Defaults to full history ({@code clone-depth: 0}). */
    private TransportCacheConfig server = new TransportCacheConfig();

    /** Default depth for the transparent-proxy mirror when neither knob is configured. */
    public static final int DEFAULT_PROXY_CLONE_DEPTH = 100;

    /** Default depth for the server-mode mirror when neither knob is configured (full history). */
    public static final int DEFAULT_SERVER_CLONE_DEPTH = 0;

    /** Clone-depth / shallow-since settings for one proxy mode's local mirror. */
    @Data
    public static class TransportCacheConfig {

        /**
         * Commit depth for the shallow clone; {@code 0} means full history. {@code null} (the default) means "unset" —
         * the mode's own default applies ({@link #DEFAULT_PROXY_CLONE_DEPTH} for proxy,
         * {@link #DEFAULT_SERVER_CLONE_DEPTH} for server mode). Ignored entirely when {@link #shallowSince} is set.
         * Modelled as a nullable {@link Integer} so an explicit {@code 0} (full) is distinguishable from an omitted
         * key.
         */
        private Integer cloneDepth;

        /**
         * Time-based shallow boundary such as {@code 90d}. When set, it takes precedence over {@link #cloneDepth} and
         * the mirror keeps history back to that point. Blank (the default) means unset.
         */
        private String shallowSince = "";

        /**
         * Resolves the effective clone depth for this mode, applying {@code modeDefault} when {@link #cloneDepth} is
         * unset. Callers should prefer {@link #resolveShallowSince()} and only fall back to this when it returns empty.
         */
        public int resolveCloneDepth(int modeDefault) {
            return cloneDepth != null ? cloneDepth : modeDefault;
        }

        /**
         * Resolves the configured {@code shallow-since} to a {@link Duration}, or {@code null} when unset. Throws
         * {@link IllegalArgumentException} on an unparseable value so a typo fails loudly at startup rather than
         * silently cloning full history.
         */
        public Duration resolveShallowSince() {
            return parseDuration(shallowSince);
        }
    }

    /** Delegates to the shared parser so every duration key in the YAML accepts the same shorthand. */
    static Duration parseDuration(String raw) {
        return DurationSettings.parse(raw, "cache shallow-since");
    }
}
