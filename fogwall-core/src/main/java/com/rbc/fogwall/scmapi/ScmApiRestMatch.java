package com.rbc.fogwall.scmapi;

/**
 * A REST allowlist match: the fogwall-internal operation name and the {@code owner/repo} the request targets.
 *
 * <p>Shared by every path-addressed REST dialect ({@link GitLabRestAllowlist}, {@link ForgejoRestAllowlist}) because it
 * carries no provider-specific semantics — the allowlists themselves stay separate, since the endpoint tables are what
 * actually differ per platform.
 */
public record ScmApiRestMatch(String operation, OwnerRepo ownerRepo) {}
