package com.rbc.fogwall.servlet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rbc.fogwall.scmapi.GitLabRestAllowlist;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

class ScmApiRestPathTest {

    private static HttpServletRequest request(String uri, String contextPath, String servletPath, String pathInfo) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getContextPath()).thenReturn(contextPath);
        when(request.getServletPath()).thenReturn(servletPath);
        when(request.getPathInfo()).thenReturn(pathInfo);
        return request;
    }

    @Test
    void stripsTheDialectMountPoint() {
        var req = request("/api/v4/projects/acme%2Fwidgets/issues", "", "/api/v4", "/projects/acme/widgets/issues");
        assertEquals("/projects/acme%2Fwidgets/issues", ScmApiRestPath.rawSubPath(req));
    }

    /**
     * The reason this helper exists at all. {@code getPathInfo()} is decoded per the servlet spec, so GitLab's
     * {@code acme%2Fwidgets} project segment arrives as two segments and the allowlist stops matching — turning every
     * {@code glab} mutation into a fail-closed denial. Matching the raw URI keeps the single segment intact.
     */
    @Test
    void decodedPathInfoBreaksTheGitLabAllowlist_rawUriDoesNot() {
        var req = request("/api/v4/projects/acme%2Fwidgets/issues", "", "/api/v4", "/projects/acme/widgets/issues");

        assertTrue(
                GitLabRestAllowlist.match("POST", req.getPathInfo()).isEmpty(),
                "decoded pathInfo must not match — if it ever does, this helper is no longer load-bearing");

        var match = GitLabRestAllowlist.match("POST", ScmApiRestPath.rawSubPath(req));
        assertTrue(match.isPresent());
        assertEquals("acme", match.get().ownerRepo().owner());
        assertEquals("widgets", match.get().ownerRepo().name());
    }

    @Test
    void handlesForgejoTwoSegmentRepoPaths() {
        var req = request("/api/v1/repos/acme/widgets/issues", "", "/api/v1", "/repos/acme/widgets/issues");
        assertEquals("/repos/acme/widgets/issues", ScmApiRestPath.rawSubPath(req));
    }

    @Test
    void accountsForANonRootContextPath() {
        var req = request("/fogwall/api/v1/repos/acme/widgets/issues", "/fogwall", "/api/v1", "/repos/acme/widgets");
        assertEquals("/repos/acme/widgets/issues", ScmApiRestPath.rawSubPath(req));
    }

    @Test
    void returnsEmptyForANullUri() {
        assertEquals("", ScmApiRestPath.rawSubPath(request(null, "", "/api/v1", null)));
    }

    @Test
    void fallsBackToTheFullUriWhenThePrefixDoesNotMatch() {
        var req = request("/unexpected/path", "", "/api/v1", null);
        assertEquals("/unexpected/path", ScmApiRestPath.rawSubPath(req));
    }
}
