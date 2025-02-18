package com.github.coopernetes.jgitproxy.servlet.filter;

import com.github.coopernetes.jgitproxy.git.HttpAuthScheme;
import jakarta.servlet.http.HttpServletRequest;

public interface AuthenticationRequiredFilter {

    boolean isAuthenticated(HttpServletRequest request);

    HttpAuthScheme requiredAuthScheme();

    default HttpAuthScheme getAuthScheme(HttpServletRequest request) {
        if (request.getHeader("Authorization").startsWith("Basic")) {
            return HttpAuthScheme.BASIC;
        }
        if (request.getHeader("Authorization").startsWith("Bearer")) {
            return HttpAuthScheme.BEARER;
        }
        throw new IllegalArgumentException("No authentication scheme found");
    }
}
