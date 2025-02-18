package com.github.coopernetes.jgitproxy.provider;

import java.net.URI;

/** An upstream Git server that will be proxied by the application. */
public abstract class AbstractGitProxyProvider implements GitProxyProvider {

    protected final String name;
    protected final URI uri;
    private String customPath = null;

    protected AbstractGitProxyProvider(String name, URI uri) {
        this.name = name;
        this.uri = uri;
    }

    protected AbstractGitProxyProvider(String name, URI uri, String customPath) {
        this.name = name;
        this.uri = uri;
        this.customPath = customPath;
    }

    /**
     * Returns the path that the servlet will be mapped to. This is based on the host of the target URL or a custom path
     * if set.
     *
     * @return the servlet path to map to
     */
    @Override
    public String servletPath() {
        return customPath != null ? customPath : "/" + uri.getHost();
    }

    /**
     * Returns the servlet mapping for the provider. This is used to map the servlet to a specific path in the
     * application. If the provider has a custom path set, this will be used as the mapping. Otherwise, the host of the
     * target URL will be used. Since this mapping is always used for setting up underlying proxying servlet, the
     * mapping will always append a wildcard end of the path to ensure that all matching requests are proxied.
     *
     * <p>Matcher functions should use {@link #servletPath()} instead.
     *
     * @return the servlet mapping for the provider
     */
    @Override
    public String servletMapping() {
        return servletPath() + "/*";
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public URI getUri() {
        return uri;
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + "{" + "name='"
                + name + '\'' + ", uri="
                + uri + ", customPath='"
                + customPath + '\'' + '}';
    }
}
