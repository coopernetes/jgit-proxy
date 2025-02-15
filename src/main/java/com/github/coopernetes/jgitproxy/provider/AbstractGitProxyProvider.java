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
     * if set. Since this path is used for setting up the servlet's URL mapping, it will always append a wildcard to the
     * end of the path to ensure that all requests are proxied to the target URL.
     *
     * @return the servlet path to map to
     */
    @Override
    public String servletPath() {
        return String.format("%s/*", customPath != null ? customPath : "/" + uri.getHost());
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
