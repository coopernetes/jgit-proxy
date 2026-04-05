package org.finos.gitproxy.servlet.filter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BasicAuthChallengeFilterTest {

    BasicAuthChallengeFilter filter;

    @BeforeEach
    void setUp() {
        filter = new BasicAuthChallengeFilter();
    }

    private HttpServletRequest receivePackRequest(String authHeader) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("POST");
        when(req.getRequestURI()).thenReturn("/git-receive-pack");
        when(req.getContentType()).thenReturn("application/x-git-receive-pack-request");
        when(req.getHeader("Authorization")).thenReturn(authHeader);
        return req;
    }

    private HttpServletRequest infoRefsReceivePackRequest(String authHeader) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("GET");
        when(req.getRequestURI()).thenReturn("/info/refs");
        when(req.getParameter("service")).thenReturn("git-receive-pack");
        when(req.getHeader("Authorization")).thenReturn(authHeader);
        return req;
    }

    private HttpServletRequest fetchRequest(String authHeader) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("POST");
        when(req.getRequestURI()).thenReturn("/git-upload-pack");
        when(req.getContentType()).thenReturn("application/x-git-upload-pack-request");
        when(req.getHeader("Authorization")).thenReturn(authHeader);
        return req;
    }

    private HttpServletRequest infoRefsFetchRequest() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("GET");
        when(req.getRequestURI()).thenReturn("/info/refs");
        when(req.getParameter("service")).thenReturn("git-upload-pack");
        when(req.getHeader("Authorization")).thenReturn(null);
        return req;
    }

    private static String basicAuth(String user, String token) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + token).getBytes());
    }

    // ---- receive-pack without auth → 401 + WWW-Authenticate ----

    @Test
    void receivePack_noAuth_challenges() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        HttpServletRequest req = receivePackRequest(null);
        HttpServletResponse resp = mock(HttpServletResponse.class);

        filter.doFilter(req, resp, chain);

        verify(resp).setHeader("WWW-Authenticate", "Basic realm=\"git-proxy\"");
        verify(resp).sendError(HttpServletResponse.SC_UNAUTHORIZED);
        verifyNoInteractions(chain);
    }

    // ---- receive-pack with blank auth header → 401 ----

    @Test
    void receivePack_blankAuth_challenges() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        HttpServletRequest req = receivePackRequest("   ");
        HttpServletResponse resp = mock(HttpServletResponse.class);

        filter.doFilter(req, resp, chain);

        verify(resp).sendError(HttpServletResponse.SC_UNAUTHORIZED);
        verifyNoInteractions(chain);
    }

    // ---- receive-pack with valid Basic auth → passes through ----

    @Test
    void receivePack_withAuth_passesThrough() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        HttpServletRequest req = receivePackRequest(basicAuth("alice", "token123"));
        HttpServletResponse resp = mock(HttpServletResponse.class);

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        verify(resp, never()).sendError(anyInt());
    }

    // ---- fetch (upload-pack) without auth → passes through (public repos) ----

    @Test
    void fetch_noAuth_passesThrough() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        HttpServletRequest req = fetchRequest(null);
        HttpServletResponse resp = mock(HttpServletResponse.class);

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        verify(resp, never()).sendError(anyInt());
    }

    // ---- info/refs for fetch (git-upload-pack) without auth → passes through ----

    @Test
    void infoRefs_fetch_noAuth_passesThrough() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        HttpServletRequest req = infoRefsFetchRequest();
        HttpServletResponse resp = mock(HttpServletResponse.class);

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        verify(resp, never()).sendError(anyInt());
    }

    // ---- info/refs?service=git-receive-pack without auth → 401 ----

    @Test
    void infoRefs_receivePack_noAuth_challenges() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        HttpServletRequest req = infoRefsReceivePackRequest(null);
        HttpServletResponse resp = mock(HttpServletResponse.class);

        filter.doFilter(req, resp, chain);

        verify(resp).sendError(HttpServletResponse.SC_UNAUTHORIZED);
        verifyNoInteractions(chain);
    }

    // ---- info/refs?service=git-receive-pack with auth → passes through ----

    @Test
    void infoRefs_receivePack_withAuth_passesThrough() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        HttpServletRequest req = infoRefsReceivePackRequest(basicAuth("alice", "token123"));
        HttpServletResponse resp = mock(HttpServletResponse.class);

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        verify(resp, never()).sendError(anyInt());
    }

    // ---- WWW-Authenticate header uses correct realm ----

    @Test
    void challenge_headerContainsRealm() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        HttpServletRequest req = receivePackRequest(null);
        HttpServletResponse resp = mock(HttpServletResponse.class);

        filter.doFilter(req, resp, chain);

        ArgumentCaptor<String> headerValue = ArgumentCaptor.forClass(String.class);
        verify(resp).setHeader(eq("WWW-Authenticate"), headerValue.capture());
        assertTrue(headerValue.getValue().startsWith("Basic "));
        assertTrue(headerValue.getValue().contains("realm="));
    }
}
