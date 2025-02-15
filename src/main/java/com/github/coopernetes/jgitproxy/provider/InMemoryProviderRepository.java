package com.github.coopernetes.jgitproxy.provider;

import java.util.*;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class InMemoryProviderRepository implements ProviderRepository {

    private final Map<String, AbstractGitProxyProvider> providers;

    @Override
    public AbstractGitProxyProvider getProvider(String name) {
        return providers.get(name);
    }
}
