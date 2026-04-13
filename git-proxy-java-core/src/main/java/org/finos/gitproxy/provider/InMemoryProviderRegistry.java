package org.finos.gitproxy.provider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.ToString;

/** In-memory {@link ProviderRegistry} backed by a {@link LinkedHashMap} keyed by friendly provider name. */
@ToString
public class InMemoryProviderRegistry implements ProviderRegistry {

    private final Map<String, GitProxyProvider> providers;

    /** Construct from a map of friendly name → provider. Insertion order is preserved. */
    public InMemoryProviderRegistry(Map<String, GitProxyProvider> providers) {
        this.providers = new LinkedHashMap<>(providers);
    }

    /** Construct from a list of providers; each provider's {@link GitProxyProvider#getName()} is used as the key. */
    public InMemoryProviderRegistry(List<GitProxyProvider> providerList) {
        this.providers = new LinkedHashMap<>();
        for (GitProxyProvider p : providerList) {
            providers.put(p.getName(), p);
        }
    }

    @Override
    public GitProxyProvider getProvider(String name) {
        return providers.get(name);
    }

    @Override
    public List<GitProxyProvider> getProviders() {
        return new ArrayList<>(providers.values());
    }
}
