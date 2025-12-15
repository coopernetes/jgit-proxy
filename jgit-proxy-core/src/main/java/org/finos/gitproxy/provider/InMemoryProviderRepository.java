package org.finos.gitproxy.provider;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

@RequiredArgsConstructor
@ToString
public class InMemoryProviderRepository implements ProviderRepository {

    private final Map<String, GitProxyProvider> providers;

    @Override
    public GitProxyProvider getProvider(String name) {
        return providers.get(name);
    }

    @Override
    public List<GitProxyProvider> getProviders() {
        return providers.values().stream().toList();
    }
}
