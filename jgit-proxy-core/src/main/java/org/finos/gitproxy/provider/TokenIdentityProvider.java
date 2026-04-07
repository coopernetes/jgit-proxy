package org.finos.gitproxy.provider;

import java.util.Optional;

/**
 * Implemented by providers that support resolving a push identity from a token by calling their user API (e.g.
 * {@code GET /user}).
 *
 * <p>Each implementation is responsible for:
 *
 * <ul>
 *   <li>Constructing the correct API endpoint URL
 *   <li>Choosing the right auth header format ({@code token}, {@code Bearer}, etc.)
 *   <li>Extracting the normalised {@link ScmUserInfo} from the response
 *   <li>Returning {@code Optional.empty()} on any HTTP or parsing error, including missing token scopes
 * </ul>
 *
 * <p>The {@code pushUsername} is the HTTP Basic-auth username the git client supplied. Most providers ignore it (they
 * authenticate solely by token). Bitbucket requires it as the email address for its Basic-auth API call.
 */
public interface TokenIdentityProvider {

    /**
     * Fetch the SCM identity associated with the given credentials.
     *
     * @param pushUsername the HTTP Basic-auth username supplied by the git client (may be ignored by most providers)
     * @param token the HTTP Basic-auth password / personal access token supplied by the git client
     * @return the resolved identity, or empty if the token is invalid, expired, or lacks the required scope
     */
    Optional<ScmUserInfo> fetchScmIdentity(String pushUsername, String token);
}
