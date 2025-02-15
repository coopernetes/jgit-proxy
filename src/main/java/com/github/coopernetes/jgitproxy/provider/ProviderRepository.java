package com.github.coopernetes.jgitproxy.provider;

public interface ProviderRepository {

    AbstractGitProxyProvider getProvider(String name);
}
