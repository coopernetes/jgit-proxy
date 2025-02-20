package com.rbc.jgitproxy.servlet.filter;

import com.rbc.jgitproxy.git.HttpOperation;
import com.rbc.jgitproxy.provider.GitProxyProvider;

import java.util.Set;

public abstract class ProviderSpecificGitProxyFilter<P extends GitProxyProvider>
        extends AbstractProviderAwareGitProxyFilter {

    protected final P provider;

    public ProviderSpecificGitProxyFilter(int order, Set<HttpOperation> appliedOperations, P provider) {
        super(order, appliedOperations, provider);
        this.provider = provider;
    }
}
