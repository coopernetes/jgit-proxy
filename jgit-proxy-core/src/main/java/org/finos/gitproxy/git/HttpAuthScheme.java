package org.finos.gitproxy.git;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum HttpAuthScheme {
    BASIC("Basic"),
    BEARER("Bearer"),
    TOKEN("token");

    private final String headerValue;
}
