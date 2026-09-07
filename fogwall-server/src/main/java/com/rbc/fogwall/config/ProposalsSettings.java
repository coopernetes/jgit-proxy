package com.rbc.fogwall.config;

import lombok.Data;

/**
 * Binds the {@code proposals:} block in fogwall.yml — global settings for proposing changes through fogwall (opening
 * and iterating on pull/merge requests), applying across all providers. Per-provider enablement lives under
 * {@code providers.<name>.proposals} (see {@link ProposalsProviderSettings}), mirroring how {@code scm-oauth:} splits
 * global settings from per-provider app registration.
 */
@Data
public class ProposalsSettings {

    /**
     * TTL for the node-ID → owner/repo resolution cache, an ISO-8601 duration (e.g. {@code PT5M}). This is a security
     * parameter, not just a perf knob — see docs/internals/SCM_API_PROXY.md: a node ID can outlive a repo
     * rename/transfer while the owner/repo it resolves to changes underneath it. Kept conservative by default.
     */
    private String nodeIdCacheTtl = "PT5M";

    /**
     * Literals and patterns refused in the prose a proposal carries — a pull/merge request title or description, a
     * comment body. Empty by default. Secret scanning is separate, keying off the global {@code secret-scan} settings.
     */
    private BlockSettings block = new BlockSettings();
}
