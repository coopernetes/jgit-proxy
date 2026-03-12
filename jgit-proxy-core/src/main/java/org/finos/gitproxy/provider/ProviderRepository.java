package org.finos.gitproxy.provider;

import java.util.List;

public interface ProviderRepository {

    GitProxyProvider getProvider(String name);

    List<GitProxyProvider> getProviders();
}
