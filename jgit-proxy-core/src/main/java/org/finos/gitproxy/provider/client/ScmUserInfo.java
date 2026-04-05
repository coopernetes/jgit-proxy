package org.finos.gitproxy.provider.client;

import java.util.Optional;

/**
 * Normalised SCM identity returned by {@link org.finos.gitproxy.provider.TokenIdentityProvider#fetchScmIdentity}.
 *
 * <p>{@code login} is the provider-assigned username (stable, URL-safe). {@code email} is the primary email address
 * associated with the account — optional because some providers hide it by default (e.g. GitHub when the user has set
 * email visibility to private).
 */
public record ScmUserInfo(String login, Optional<String> email) {}
