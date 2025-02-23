package org.finos.gitproxy.provider;

import java.net.URI;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;

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
     * if set along with an optional application-wide base path. To configure a {@link org.finos.gitproxy.servlet.GitProxyProviderServlet}
     * for proxying, use {@link #servletMapping()} instead.
     * @return The base path that this provider will be mapped to.
     */
    @Override
    public String servletPath() {
        return basePath + (customPath != null ? customPath : "/" + uri.getHost());
    }

    /**
     * Returns the servlet mapping for the provider. This is used to map the servlet to a specific path in the
     * application. Since this mapping is always used for setting up underlying proxying servlet, the
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
