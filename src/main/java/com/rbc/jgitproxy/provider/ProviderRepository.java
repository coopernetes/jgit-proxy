package com.rbc.jgitproxy.provider;

import java.util.List;

public interface ProviderRepository {

    GitProxyProvider getProvider(String name);

    List<GitProxyProvider> getProviders();
}
