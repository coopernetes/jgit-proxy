package com.rbc.fogwall.service;

import com.rbc.fogwall.provider.FogwallProvider;
import com.rbc.fogwall.user.UserEntry;
import java.util.Optional;

/**
 * Resolves the proxy {@link UserEntry} for an incoming git push.
 *
 * <p>Implementations are responsible for mapping push credentials (provider, HTTP Basic username, token) to a known
 * proxy user. The token takes precedence over the username because providers like GitHub and GitLab do not validate the
 * Basic-auth username — only the token is meaningful for identity.
 *
 * <p>Built-in implementations:
 *
 * <ul>
 *   <li>{@link TokenPushIdentityResolver} — resolves by calling the provider's user API with the token, then matching
 *       the returned SCM login against {@code user_scm_identities}.
 *   <li>{@link ChainedPushIdentityResolver} — tries a list of resolvers in order; intended for multi-SCM environments
 *       where different identity sources must be consulted (see coopernetes/fogwall#125).
 * </ul>
 */
public interface PushIdentityResolver {

    /**
     * Resolve a proxy user from push credentials.
     *
     * @param provider the provider name (e.g. {@code "github"}, {@code "gitlab"})
     * @param pushUsername the HTTP Basic-auth username supplied by the git client (may be arbitrary — providers do not
     *     validate it)
     * @param token the HTTP Basic-auth password/token
     * @return the resolved proxy user, or empty if no match is found
     */
    Optional<UserEntry> resolve(FogwallProvider provider, String pushUsername, String token);

    /**
     * As {@link #resolve}, also naming the provider account the credential belongs to.
     *
     * <p>Separate from {@code resolve} because the two answer different questions. Authorization needs the fogwall user
     * and nothing else; an audit record needs to name the account that acted, and that cannot be derived from the user
     * afterwards — a user may hold several identities on one provider, and nothing in the resolved {@link UserEntry}
     * says which of them the credential was.
     *
     * <p>The default returns the user with no login, for resolvers that map credentials to users by some other means
     * than an SCM lookup. Callers must treat a null {@code scmLogin} as "not determined", never as "no account".
     */
    default Optional<ResolvedScmIdentity> resolveIdentity(FogwallProvider provider, String pushUsername, String token) {
        return resolve(provider, pushUsername, token).map(user -> new ResolvedScmIdentity(user, null));
    }
}
