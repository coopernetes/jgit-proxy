package com.rbc.fogwall.servlet.filter;

import static com.rbc.fogwall.servlet.ScmApiRequestContext.SCM_API_REQUEST_ATTR;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rbc.fogwall.db.model.ScmApiActionStatus;
import com.rbc.fogwall.permission.RepoPermissionService;
import com.rbc.fogwall.provider.GitHubProvider;
import com.rbc.fogwall.scmapi.GitHubNodeIdResolver;
import com.rbc.fogwall.scmapi.MutationNodeIdRef;
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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScmApiGitHubGateFilterTest {

    private final GitHubProvider provider = new GitHubProvider("/scm-api/github.com");
    private GitHubNodeIdResolver gitHubNodeIdResolver;
    private RepoPermissionService repoPermissionService;

    @BeforeEach
    void setUp() {
        gitHubNodeIdResolver = mock(GitHubNodeIdResolver.class);
        repoPermissionService = mock(RepoPermissionService.class);
    }

    private ScmApiGitHubGateFilter filter() {
        return new ScmApiGitHubGateFilter(provider, gitHubNodeIdResolver, repoPermissionService);
    }

    private static HttpServletRequest mockRequest(String body, ScmApiRequestContext context) throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
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
    void malformedBody_returns400_doesNotCallChain() throws Exception {
        var context = new ScmApiRequestContext();
        HttpServletRequest req = mockRequest("not json", context);
        HttpServletResponse resp = mockResponse(new ByteArrayOutputStream());
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        verify(resp).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        verifyNoInteractions(chain);
    }

    @Test
    void read_continuesChain() throws Exception {
        var context = new ScmApiRequestContext();
        HttpServletRequest req = mockRequest("{\"query\":\"query { viewer { login } }\"}", context);
        HttpServletResponse resp = mockResponse(new ByteArrayOutputStream());
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        verify(chain).doFilter(any(), eq(resp));
        assertNull(context.getMutationField());
    }

    @Test
    void unallowlistedMutation_returns403_deniesWithReason() throws Exception {
        var context = new ScmApiRequestContext();
        String body =
                "{\"query\":\"mutation { deleteRepository(input: {repositoryId: \\\"R_1\\\"}) { clientMutationId } }\"}";
        HttpServletRequest req = mockRequest(body, context);
        HttpServletResponse resp = mockResponse(new ByteArrayOutputStream());
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        verify(resp).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verifyNoInteractions(chain);
        assertEquals(ScmApiActionStatus.DENIED, context.getStatus());
        assertEquals("deleteRepository", context.getMutationField());
    }

    @Test
    void mutationMissingNodeIdVariable_returns400() throws Exception {
        var context = new ScmApiRequestContext();
        String body =
                "{\"query\":\"mutation($input: CreateIssueInput!) { createIssue(input: $input) { issue { id } } }\",\"variables\":{}}";
        HttpServletRequest req = mockRequest(body, context);
        HttpServletResponse resp = mockResponse(new ByteArrayOutputStream());
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        verify(resp).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        verifyNoInteractions(chain);
    }

    @Test
    void nodeIdResolutionFails_returns403() throws Exception {
        when(gitHubNodeIdResolver.resolve(eq(provider), any(MutationNodeIdRef.class), eq("caller-token")))
                .thenReturn(Optional.empty());
        var context = new ScmApiRequestContext();
        String body =
                "{\"query\":\"mutation($input: CreateIssueInput!) { createIssue(input: $input) { issue { id } } }\",\"variables\":{\"input\":{\"repositoryId\":\"R_1\"}}}";
        HttpServletRequest req = mockRequest(body, context);
        HttpServletResponse resp = mockResponse(new ByteArrayOutputStream());
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        verify(resp).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verifyNoInteractions(chain);
    }

    /**
     * The node-ID cache is shared between users, so a denial naming what an ID resolved to would answer a question the
     * caller's own token may not have been able to: send an opaque ID, read the repository name out of the refusal.
     */
    @Test
    void notPermitted_doesNotTellTheCallerWhichRepositoryTheIdResolvedTo() throws Exception {
        when(gitHubNodeIdResolver.resolve(eq(provider), any(MutationNodeIdRef.class), eq("caller-token")))
                .thenReturn(Optional.of(new OwnerRepo("acme", "secret-repo")));
        when(repoPermissionService.isAllowedToPropose("alice", "github", "/acme/secret-repo"))
                .thenReturn(false);
        var context = new ScmApiRequestContext();
        context.setResolvedUser("alice");
        String body =
                "{\"query\":\"mutation($input: CreateIssueInput!) { createIssue(input: $input) { issue { id } } }\",\"variables\":{\"input\":{\"repositoryId\":\"R_1\"}}}";
        HttpServletRequest req = mockRequest(body, context);
        var out = new ByteArrayOutputStream();
        HttpServletResponse resp = mockResponse(out);

        filter().doFilter(req, resp, mock(FilterChain.class));

        verify(resp).setStatus(HttpServletResponse.SC_FORBIDDEN);
        assertFalse(out.toString(StandardCharsets.UTF_8).contains("secret-repo"), out.toString(StandardCharsets.UTF_8));
        assertTrue(context.getReason().contains("secret-repo"), "the audit record still names it");
    }

    @Test
    void resolvedButNotPermitted_returns403() throws Exception {
        when(gitHubNodeIdResolver.resolve(eq(provider), any(MutationNodeIdRef.class), eq("caller-token")))
                .thenReturn(Optional.of(new OwnerRepo("acme", "widgets")));
        when(repoPermissionService.isAllowedToPropose("alice", "github", "/acme/widgets"))
                .thenReturn(false);
        var context = new ScmApiRequestContext();
        context.setResolvedUser("alice");
        String body =
                "{\"query\":\"mutation($input: CreateIssueInput!) { createIssue(input: $input) { issue { id } } }\",\"variables\":{\"input\":{\"repositoryId\":\"R_1\"}}}";
        HttpServletRequest req = mockRequest(body, context);
        HttpServletResponse resp = mockResponse(new ByteArrayOutputStream());
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        verify(resp).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verifyNoInteractions(chain);
    }

    @Test
    void allChecksPass_continuesChain_populatesContext() throws Exception {
        when(gitHubNodeIdResolver.resolve(eq(provider), any(MutationNodeIdRef.class), eq("caller-token")))
                .thenReturn(Optional.of(new OwnerRepo("acme", "widgets")));
        when(repoPermissionService.isAllowedToPropose("alice", "github", "/acme/widgets"))
                .thenReturn(true);
        var context = new ScmApiRequestContext();
        context.setResolvedUser("alice");
        String body =
                "{\"query\":\"mutation($input: CreateIssueInput!) { createIssue(input: $input) { issue { id } } }\",\"variables\":{\"input\":{\"repositoryId\":\"R_1\"}}}";
        HttpServletRequest req = mockRequest(body, context);
        HttpServletResponse resp = mockResponse(new ByteArrayOutputStream());
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        verify(chain).doFilter(any(), eq(resp));
        assertEquals("createIssue", context.getMutationField());
        assertEquals("R_1", context.getNodeId());
        assertEquals("acme", context.getRepoOwner());
        assertEquals("widgets", context.getRepoName());
    }
}
