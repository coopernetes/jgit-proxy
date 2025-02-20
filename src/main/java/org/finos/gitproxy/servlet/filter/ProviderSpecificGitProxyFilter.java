package org.finos.gitproxy.servlet.filter;

import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.provider.GitProxyProvider;

import java.util.Set;

public abstract class ProviderSpecificGitProxyFilter<P extends GitProxyProvider>
        extends AbstractProviderAwareGitProxyFilter {

    protected final P provider;

    public ProviderSpecificGitProxyFilter(int order, Set<HttpOperation> appliedOperations, P provider) {
        super(order, appliedOperations, provider);
        this.provider = provider;
    }
}
