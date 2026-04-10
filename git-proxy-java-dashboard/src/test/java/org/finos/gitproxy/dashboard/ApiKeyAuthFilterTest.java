package org.finos.gitproxy.dashboard;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

class ApiKeyAuthFilterTest {

    ApiKeyAuthFilter filter;
    HttpServletRequest request;
    HttpServletResponse response;
    FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthFilter("secret-key");
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @Test
    void correctKey_authenticatesAsAdmin() throws Exception {
        when(request.getHeader(ApiKeyAuthFilter.HEADER)).thenReturn("secret-key");

        filter.doFilterInternal(request, response, chain);

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertTrue(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        verify(chain).doFilter(request, response);
    }

    @Test
    void wrongKey_doesNotAuthenticate() throws Exception {
        when(request.getHeader(ApiKeyAuthFilter.HEADER)).thenReturn("wrong-key");

        filter.doFilterInternal(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(request, response);
    }

    @Test
    void missingHeader_doesNotAuthenticate() throws Exception {
        when(request.getHeader(ApiKeyAuthFilter.HEADER)).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(request, response);
    }

    @Test
    void emptyKey_doesNotAuthenticate() throws Exception {
        when(request.getHeader(ApiKeyAuthFilter.HEADER)).thenReturn("");

        filter.doFilterInternal(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(request, response);
    }

    @Test
    void keyWithMatchingLengthButDifferentContent_doesNotAuthenticate() throws Exception {
        // Same length as "secret-key" (10 chars) but different — ensures constant-time comparison
        // doesn't short-circuit and inadvertently admit a same-length wrong key.
        when(request.getHeader(ApiKeyAuthFilter.HEADER)).thenReturn("secret-kex");

        filter.doFilterInternal(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(request, response);
    }
}
