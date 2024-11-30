package com.github.coopernetes.jgitproxy.servlet;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum GitHttpProviders {
    GITHUB("github.com"),
    GITLAB("gitlab.com"),
    BITBUCKET("bitbucket.org");

    private final String hostname;
}
