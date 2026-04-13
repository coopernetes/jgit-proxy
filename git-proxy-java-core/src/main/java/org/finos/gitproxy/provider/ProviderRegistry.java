package org.finos.gitproxy.provider;

import java.util.List;

/**
 * Registry of configured git proxy providers. Keyed by the friendly name used as the config map key (e.g.
 * {@code github}, {@code internal-gitlab}), independent of the internal {@code type/host} provider ID used for request
 * routing.
 *
 * <p>Consistent with {@link org.finos.gitproxy.db.RepoRegistry} — a lookup/discovery mechanism, not a CRUD store.
 */
public interface ProviderRegistry {

    /**
     * Look up a provider by its friendly name (the YAML map key, e.g. {@code "github"}).
     *
     * @return the provider, or {@code null} if not found
     */
    GitProxyProvider getProvider(String name);

    /** Returns all registered providers. */
    List<GitProxyProvider> getProviders();

    /**
     * Resolves a provider by either its friendly name (e.g. {@code "github"}) or its canonical {@code type/host} ID
     * (e.g. {@code "github/github.com"}). Friendly name is tried first.
     *
     * <p>This is the entry point for validating config references in {@code permissions:}, {@code rules:}, and
     * {@code scm-identities:} — both forms are accepted so operators can use whichever is more readable.
     *
     * @return the provider, or {@code null} if neither a friendly name nor an ID match
     */
    default GitProxyProvider resolveProvider(String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) return null;
        GitProxyProvider byName = getProvider(nameOrId);
        if (byName != null) return byName;
        return getProviders().stream()
                .filter(p -> p.getProviderId().equals(nameOrId))
                .findFirst()
                .orElse(null);
    }
}
