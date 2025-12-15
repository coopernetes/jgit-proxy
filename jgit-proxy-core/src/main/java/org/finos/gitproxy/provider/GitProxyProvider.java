package org.finos.gitproxy.provider;

import java.net.URI;

public interface GitProxyProvider {

    String getName();

    URI getUri();

    String servletPath();

    String servletMapping();
}
