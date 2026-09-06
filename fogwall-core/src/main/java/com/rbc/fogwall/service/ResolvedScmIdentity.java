package com.rbc.fogwall.service;

import com.rbc.fogwall.user.UserEntry;

/**
 * The outcome of resolving push or API credentials: the fogwall user, and the provider account the credential actually
 * named.
 *
 * <p>{@link PushIdentityResolver#resolve} returns only the user, which is all an authorization decision needs. An audit
 * record needs the other half too — which upstream account performed the write — and that cannot be recovered from the
 * user afterwards, because a user may hold several identities on one provider.
 *
 * @param user the fogwall user the credential resolved to
 * @param scmLogin the caller's login on the provider, or null when the resolver could not determine it
 */
public record ResolvedScmIdentity(UserEntry user, String scmLogin) {}
