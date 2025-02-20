package com.rbc.jgitproxy.provider;

import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;

import java.net.URI;

/** An upstream Git server that will be proxied by the application. */
@RequiredArgsConstructor
@AllArgsConstructor
public abstract class AbstractGitProxyProvider implements GitProxyProvider {

    protected final String name;
    protected final URI uri;
    protected final String basePath;
    protected String customPath;

    /**
     * Returns the path that the servlet will be mapped to. This is based on the host of the target URL or a custom path
     * if set.
     *
     * @return the servlet path to map to
     */
    @Override
    public String servletPath() {
        return basePath + (customPath != null ? customPath : "/" + uri.getHost());
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
    public final String servletMapping() {
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
                + uri + ", basePath='"
                + basePath + '\'' + ", customPath='"
                + customPath + '\'' + '}';
    }
}
