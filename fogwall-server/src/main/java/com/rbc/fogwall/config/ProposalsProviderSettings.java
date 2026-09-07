package com.rbc.fogwall.config;

import lombok.Data;

/**
 * Proposal settings for a single provider instance, nested under {@code providers.<name>.proposals}. See
 * docs/CONFIGURATION.md#proposals and docs/internals/SCM_API_PROXY.md.
 *
 * <p>Opt-in per provider, default {@code false}: a deployment that doesn't proxy pull requests pays zero
 * registration/runtime cost for it, per CLAUDE.md's "don't raise baseline complexity for non-users" principle.
 */
@Data
public class ProposalsProviderSettings {

    /** Whether fogwall proxies proposal traffic for this provider. */
    private boolean enabled = false;

    /**
     * Dedicated listener port for this provider. Required when {@link #enabled} is set.
     *
     * <p>Each provider gets its own port because the CLIs cannot be redirected any other way: {@code gh} and {@code fj}
     * address the API from the host root and silently discard any path prefix (verified — see
     * docs/internals/SCM_API_PROXY.md's "Client redirection" section), so the dialect must be mounted at {@code /} on a
     * listener of its own. A port per provider also keeps two instances of the same platform from colliding, since
     * every GitLab speaks {@code /api/v4} and every Gitea/Forgejo speaks {@code /api/v1}.
     */
    private int port = 0;

    /**
     * Refuse any caller whose {@code User-Agent} isn't one of the recognised SCM CLIs — browsers, bare {@code curl},
     * unrecognised automation. Default {@code false}.
     *
     * <p>Purely subtractive hardening, never a security boundary: {@code User-Agent} is caller-controlled, so this can
     * only deny a request that would otherwise be allowed, never permit one the allowlist and permission engine would
     * refuse. Left off by default because a CLI release that changes its {@code User-Agent} format would otherwise
     * start failing for reasons unrelated to policy.
     */
    private boolean requireKnownCli = false;
}
