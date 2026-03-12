package org.finos.gitproxy.servlet.filter;

import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Stream;
import org.finos.gitproxy.git.HttpAuthScheme;

public interface AuthenticationRequiredFilter {

    boolean isAuthenticated(HttpServletRequest request);

    boolean isUsingRequiredAuthScheme(HttpServletRequest request);

    default HttpAuthScheme getAuthScheme(HttpServletRequest request) {
        return Stream.of(HttpAuthScheme.values())
                .filter(scheme -> request.getHeader("Authorization").startsWith(scheme.getHeaderValue()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No authentication scheme found"));
    }
}
