package com.rbc.jgitproxy.git;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum HttpAuthScheme {
    BASIC("Basic"),
    BEARER("Bearer");

    private final String scheme;
}
