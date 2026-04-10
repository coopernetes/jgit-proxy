package org.finos.gitproxy.service;

import java.util.Optional;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.user.UserEntry;

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
 *       where different identity sources must be consulted (see coopernetes/git-proxy-java#125).
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
    Optional<UserEntry> resolve(GitProxyProvider provider, String pushUsername, String token);
}
