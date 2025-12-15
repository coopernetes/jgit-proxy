package org.finos.gitproxy.config;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.finos.gitproxy.provider.GitProxyProvider;

/** Simple in-memory implementation of ProviderConfigurationSource */
@RequiredArgsConstructor
public class InMemoryProviderConfigurationSource implements ProviderConfigurationSource {

    private final Map<String, GitProxyProvider> providers;

    public InMemoryProviderConfigurationSource(List<GitProxyProvider> providerList) {
        this.providers = new HashMap<>();
        for (GitProxyProvider provider : providerList) {
            providers.put(provider.getName(), provider);
        }
    }

    @Override
    public List<GitProxyProvider> getProviders() {
        return new ArrayList<>(providers.values());
    }

    @Override
    public GitProxyProvider getProvider(String name) {
        return providers.get(name);
    }
}
