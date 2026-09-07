package com.rbc.fogwall.servlet.filter;

import static com.rbc.fogwall.servlet.ScmApiRequestContext.SCM_API_REQUEST_ATTR;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rbc.fogwall.db.model.ScmApiActionStatus;
import com.rbc.fogwall.permission.RepoPermissionService;
import com.rbc.fogwall.provider.GitLabProvider;
import com.rbc.fogwall.scmapi.GitLabProjectIdCache;
import com.rbc.fogwall.scmapi.GitLabProjectIdResolver;
import com.rbc.fogwall.scmapi.OwnerRepo;
import com.rbc.fogwall.servlet.ScmApiRequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScmApiGitLabGateFilterTest {

    private final GitLabProvider provider = new GitLabProvider("/scm-api/gitlab.com");
    private RepoPermissionService repoPermissionService;
    private GitLabProjectIdCache projectIdCache;

    @BeforeEach
    void setUp() {
        repoPermissionService = mock(RepoPermissionService.class);
        projectIdCache = mock(GitLabProjectIdCache.class);
    }

    private ScmApiGitLabGateFilter filter() {
        return new ScmApiGitLabGateFilter(provider, new GitLabProjectIdResolver(projectIdCache), repoPermissionService);
    }

    /**
     * Pre-populates the resolver's cache so a target-project test never reaches the network. A cache hit is the normal
     * steady state anyway — the upstream call happens once per project per TTL.
     */
    private void cacheProject(String projectId, String owner, String name) {
        when(projectIdCache.lookup(provider.getProviderId(), projectId))
                .thenReturn(java.util.Optional.of(new OwnerRepo(owner, name)));
    }

    /**
     * {@code subPath} is the still-encoded path below the {@code /api/v4} mount, i.e. what the filter matches on. It is
     * supplied through {@code getRequestURI()} rather than {@code getPathInfo()} because the latter is decoded by the
     * container and would mangle the {@code owner%2Frepo} project segment — see {@code ScmApiRestPathTest}.
     */
    private static HttpServletRequest mockRequest(
            String method, String subPath, String body, ScmApiRequestContext context) throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        when(req.getMethod()).thenReturn(method);
        when(req.getRequestURI()).thenReturn("/api/v4" + subPath);
        when(req.getContextPath()).thenReturn("");
        when(req.getServletPath()).thenReturn("/api/v4");
        when(req.getInputStream()).thenReturn(streamOf(bytes));
        when(req.getContentLength()).thenReturn(bytes.length);
        when(req.getAttribute(SCM_API_REQUEST_ATTR)).thenReturn(context);
        when(req.getHeader("Authorization")).thenReturn("Bearer caller-token");
        return req;
    }

    private static ServletInputStream streamOf(byte[] bytes) {
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        return new ServletInputStream() {
            @Override
            public int read() {
                return bais.read();
            }

            @Override
            public boolean isFinished() {
                return bais.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener l) {}
        };
    }

    private static HttpServletResponse mockResponse(ByteArrayOutputStream body) throws Exception {
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(resp.getOutputStream()).thenReturn(new ServletOutputStream() {
            @Override
            public void write(int b) {
                body.write(b);
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener l) {}
        });
        return resp;
    }

    @Test
    void get_read_continuesChain() throws Exception {
        var context = new ScmApiRequestContext();
        HttpServletRequest req = mockRequest("GET", "/projects/acme%2Fwidgets/issues", "", context);
        HttpServletResponse resp = mockResponse(new ByteArrayOutputStream());
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        verify(chain).doFilter(any(), eq(resp));
        assertNull(context.getMutationField());
    }

    @Test
    void unallowlistedPath_returns403_denies() throws Exception {
        var context = new ScmApiRequestContext();
        HttpServletRequest req = mockRequest("POST", "/projects/acme%2Fwidgets/labels", "{}", context);
        HttpServletResponse resp = mockResponse(new ByteArrayOutputStream());
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        verify(resp).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verifyNoInteractions(chain);
        assertEquals(ScmApiActionStatus.DENIED, context.getStatus());
    }

    @Test
    void allowlistedMutation_deniedByAccessRule_returns403() throws Exception {
        var context = new ScmApiRequestContext();
        HttpServletRequest req = mockRequest("POST", "/projects/acme%2Fwidgets/issues", "{}", context);
        HttpServletResponse resp = mockResponse(new ByteArrayOutputStream());
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        verify(resp).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verifyNoInteractions(chain);
    }

    @Test
    void allowlistedMutation_notPermitted_returns403() throws Exception {
        when(repoPermissionService.isAllowedToPropose("alice", "gitlab", "/acme/widgets"))
                .thenReturn(false);
        var context = new ScmApiRequestContext();
        context.setResolvedUser("alice");
        HttpServletRequest req = mockRequest("POST", "/projects/acme%2Fwidgets/issues", "{}", context);
        HttpServletResponse resp = mockResponse(new ByteArrayOutputStream());
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        verify(resp).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verifyNoInteractions(chain);
    }

    @Test
    void allChecksPass_continuesChain_populatesContext() throws Exception {
        when(repoPermissionService.isAllowedToPropose("alice", "gitlab", "/acme/widgets"))
                .thenReturn(true);
        var context = new ScmApiRequestContext();
        context.setResolvedUser("alice");
        HttpServletRequest req = mockRequest("POST", "/projects/acme%2Fwidgets/issues", "{}", context);
        HttpServletResponse resp = mockResponse(new ByteArrayOutputStream());
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        verify(chain).doFilter(any(), eq(resp));
        assertEquals("issues.create", context.getMutationField());
        assertEquals("acme", context.getRepoOwner());
        assertEquals("widgets", context.getRepoName());
    }

    /**
     * A GitLab subgroup, which no other dialect has: the project segment decodes to more than two path components, and
     * the slug handed to the permission engine has to carry all of them. {@code GitLabRestAllowlist} splits on the LAST
     * separator so the whole namespace lands in owner and only the final component in name, which is what makes this
     * round-trip; splitting on the first would authorize {@code /group/subgroup} and forward to a different project.
     */
    @Test
    void nestedGroupProject_authorizesOnTheFullNamespaceSlug() throws Exception {
        when(repoPermissionService.isAllowedToPropose("alice", "gitlab", "/group/subgroup/project"))
                .thenReturn(true);
        var context = new ScmApiRequestContext();
        context.setResolvedUser("alice");
        HttpServletRequest req = mockRequest("POST", "/projects/group%2Fsubgroup%2Fproject/issues", "{}", context);
        HttpServletResponse resp = mockResponse(new ByteArrayOutputStream());
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        verify(chain).doFilter(any(), eq(resp));
        verify(repoPermissionService).isAllowedToPropose("alice", "gitlab", "/group/subgroup/project");
        assertEquals("group/subgroup", context.getRepoOwner());
        assertEquals("project", context.getRepoName());
    }

    /**
     * The fork case, and the reason the target-project resolution exists: {@code glab mr create} posts to the SOURCE
     * project and names the upstream only as a numeric {@code target_project_id} in the body. Authorizing on the URL
     * would check the fork — which the contributor owns and can always write to — instead of the upstream.
     */
    @Test
    void forkMergeRequest_authorizesOnTargetProject_notTheForkInTheUrl() throws Exception {
        cacheProject("53539888", "acme", "widgets");
        when(repoPermissionService.isAllowedToPropose("alice", "gitlab", "/acme/widgets"))
                .thenReturn(true);
        var context = new ScmApiRequestContext();
        context.setResolvedUser("alice");
        HttpServletRequest req = mockRequest(
                "POST",
                "/projects/alice%2Fwidgets-fork/merge_requests",
                "{\"source_branch\":\"f\",\"target_branch\":\"main\",\"target_project_id\":53539888}",
                context);
        HttpServletResponse resp = mockResponse(new ByteArrayOutputStream());
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        verify(chain).doFilter(any(), eq(resp));
        // The upstream, not alice/widgets-fork from the URL.
        assertEquals("acme", context.getRepoOwner());
        assertEquals("widgets", context.getRepoName());
        verify(repoPermissionService).isAllowedToPropose("alice", "gitlab", "/acme/widgets");
        verify(repoPermissionService, never()).isAllowedToPropose("alice", "gitlab", "/alice/widgets-fork");
    }

    /** Permission is checked against the upstream, so holding PROPOSE on the fork alone is not enough. */
    @Test
    void forkMergeRequest_deniedWhenUserMayProposeOnlyOnTheFork() throws Exception {
        cacheProject("53539888", "acme", "widgets");
        when(repoPermissionService.isAllowedToPropose("alice", "gitlab", "/alice/widgets-fork"))
                .thenReturn(true);
        when(repoPermissionService.isAllowedToPropose("alice", "gitlab", "/acme/widgets"))
                .thenReturn(false);
        var context = new ScmApiRequestContext();
        context.setResolvedUser("alice");
        HttpServletRequest req = mockRequest(
                "POST", "/projects/alice%2Fwidgets-fork/merge_requests", "{\"target_project_id\":53539888}", context);
        var out = new ByteArrayOutputStream();
        HttpServletResponse resp = mockResponse(out);
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        verify(chain, never()).doFilter(any(), any());
        verify(resp).setStatus(403);
    }

    /**
     * A named-but-unresolvable target denies rather than falling back to the URL: the field's presence says the URL is
     * not the target, so the fallback would authorize the wrong repository exactly when fogwall knows least.
     */
    @Test
    void unresolvableTargetProject_denies_ratherThanFallingBackToTheUrl() throws Exception {
        when(repoPermissionService.isAllowedToPropose("alice", "gitlab", "/alice/widgets-fork"))
                .thenReturn(true);
        var context = new ScmApiRequestContext();
        context.setResolvedUser("alice");
        // target_project_id present but non-numeric: unusable, and never treated as absent.
        HttpServletRequest req = mockRequest(
                "POST",
                "/projects/alice%2Fwidgets-fork/merge_requests",
                "{\"target_project_id\":\"not-an-id\"}",
                context);
        HttpServletResponse resp = mockResponse(new ByteArrayOutputStream());
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        verify(chain, never()).doFilter(any(), any());
        verify(resp).setStatus(403);
        verify(repoPermissionService, never()).isAllowedToPropose(any(), any(), any());
    }
}
