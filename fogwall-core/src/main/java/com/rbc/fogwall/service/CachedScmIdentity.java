package com.rbc.fogwall.service;

/**
 * What a token resolved to, as held in {@link ScmTokenCache}: the caller's account on the provider, and the fogwall
 * user that account belongs to.
 *
 * <p>Both halves are cached because only one of them can be recovered from the other. {@code scmLogin} is what the
 * provider itself returned for that token, and a token names the same account for its whole life — so the cache key and
 * this value are bound one-to-one. {@code proxyUsername} is derived from it, through the unique {@code (provider,
 * scm_username)} index on {@code user_scm_identities}.
 *
 * <p>Going back the other way does not work: a fogwall user may hold several identities on one provider, so the proxy
 * username alone cannot say which account the token belongs to. Storing the login is what lets an audit record name the
 * account that acted rather than guess at it.
 *
 * @param proxyUsername the fogwall user the identity is linked to
 * @param scmLogin the caller's login on the provider; null on an entry written before this was cached
 */
public record CachedScmIdentity(String proxyUsername, String scmLogin) {}
