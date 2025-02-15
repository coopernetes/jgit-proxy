package com.github.coopernetes.jgitproxy.provider;

import java.net.URI;

public interface GitProxyProvider {

    String getName();

    URI getUri();

    String servletPath();
}
