package com.rbc.fogwall.servlet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.hc.core5.http.HttpHeaders;
import org.junit.jupiter.api.Test;

/**
 * The CLIs do not agree on how to present a token: reading only {@code Authorization} rejected every {@code glab}
 * request with a 401 that came from fogwall, not from GitLab.
 */
class ScmApiTokenExtractorTest {

    private static HttpServletRequest request(String authorization, String privateToken) {
        var req = mock(HttpServletRequest.class);
        when(req.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn(authorization);
        when(req.getHeader(ScmApiTokenExtractor.PRIVATE_TOKEN)).thenReturn(privateToken);
        return req;
    }

    @Test
    void stripsRecognisedAuthorizationSchemes() {
        assertEquals("abc123", ScmApiTokenExtractor.extractToken("Bearer abc123"));
        assertEquals("abc123", ScmApiTokenExtractor.extractToken("bearer abc123"));
        assertEquals("abc123", ScmApiTokenExtractor.extractToken("token abc123"));
        assertEquals("abc123", ScmApiTokenExtractor.extractToken("  Bearer abc123  "));
    }

    @Test
    void treatsAnUnschemedValueAsTheTokenItself() {
        assertEquals("abc123", ScmApiTokenExtractor.extractToken("abc123"));
    }

    @Test
    void returnsNullWhenNoHeaderCarriesAToken() {
        assertNull(ScmApiTokenExtractor.extractToken((String) null));
        assertNull(ScmApiTokenExtractor.extractToken("   "));
        assertNull(ScmApiTokenExtractor.extractToken(request(null, null)));
        assertNull(ScmApiTokenExtractor.authHeaderName(request(null, null)));
    }

    /** {@code glab} sends a PAT in GitLab's own header and no {@code Authorization} at all (verified, glab 1.116.0). */
    @Test
    void readsGitLabsPrivateTokenHeader() {
        var req = request(null, "glpat-abc123");
        assertEquals("glpat-abc123", ScmApiTokenExtractor.extractToken(req));
        assertEquals(ScmApiTokenExtractor.PRIVATE_TOKEN, ScmApiTokenExtractor.authHeaderName(req));
    }

    @Test
    void authorizationWinsWhenBothArePresent() {
        var req = request("Bearer from-auth", "from-private");
        assertEquals("from-auth", ScmApiTokenExtractor.extractToken(req));
        assertEquals(HttpHeaders.AUTHORIZATION, ScmApiTokenExtractor.authHeaderName(req));
    }
}
