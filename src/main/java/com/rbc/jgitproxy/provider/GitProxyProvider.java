package com.rbc.jgitproxy.provider;

import java.net.URI;

public interface GitProxyProvider {

    String getName();

    URI getUri();

    String servletPath();

    String servletMapping();
}
