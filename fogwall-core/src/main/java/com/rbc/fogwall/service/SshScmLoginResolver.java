package com.rbc.fogwall.service;

import com.rbc.fogwall.provider.FogwallProvider;
import com.rbc.fogwall.user.UserEntry;
import java.util.Optional;

/**
 * Answers which SCM login an authenticated SSH connection proves, given the proxy user public-key auth resolved and the
 * fingerprint they connected with.
 *
 * <p>Implementations differ in the evidence they accept, which is what {@code scm-oauth.identity-mode} selects between:
 *
 * <ul>
 *   <li>{@link SshScmIdentityEnricher} asks the provider which keys each linked login has registered. A key the user
 *       typed into the profile page counts, so long as the provider confirms it.
 *   <li>{@link ImportedKeyIdentityResolver} accepts only what OAuth linking imported, read from fogwall's own database.
 *       A hand-added key proves nothing, and no provider call is made.
 * </ul>
 *
 * <p>Resolving is not deciding: an implementation returns a login or nothing, and never blocks a push itself. The
 * caller — {@code CheckUserPushPermissionHook} — turns an empty result into a refusal, and owns the remedy it names,
 * which differs between the two.
 */
public interface SshScmLoginResolver {

    /**
     * @param user the proxy user resolved from the connecting key at authentication time
     * @param provider the provider this push targets
     * @param connectingFingerprint SHA-256 fingerprint of the key the client authenticated with
     * @return the SCM login the connection proves, or empty when nothing does
     */
    Optional<String> resolveScmLogin(UserEntry user, FogwallProvider provider, String connectingFingerprint);
}
