package com.github.coopernetes.jgitproxy.provider;

import java.net.URI;

/**
 * A generic proxy provider that can be used to proxy any git provider. Unlike the other providers, this provider does
 * not have any specific logic for a particular git provider.
 */
public class GenericProxyProvider extends AbstractGitProxyProvider {

    public GenericProxyProvider(String name, URI uri) {
        super(name, uri);
    }

    public GenericProxyProvider(String name, URI uri, String customPath) {
        super(name, uri, customPath);
    }
}
