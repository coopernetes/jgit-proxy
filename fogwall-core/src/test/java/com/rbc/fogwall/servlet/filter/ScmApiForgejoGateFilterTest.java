package com.rbc.fogwall.servlet.filter;

import static com.rbc.fogwall.servlet.ScmApiRequestContext.SCM_API_REQUEST_ATTR;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rbc.fogwall.db.model.ScmApiActionStatus;
import com.rbc.fogwall.permission.RepoPermissionService;
import com.rbc.fogwall.provider.ForgejoProvider;
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

class ScmApiForgejoGateFilterTest {

    private final ForgejoProvider provider =
            ForgejoProvider.builder().name("gitea").uri(ForgejoProvider.GITEA).build();
    private RepoPermissionService repoPermissionService;

    @BeforeEach
    void setUp() {
        repoPermissionService = mock(RepoPermissionService.class);
    }

    private ScmApiForgejoGateFilter filter() {
        return new ScmApiForgejoGateFilter(provider, repoPermissionService);
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
        when(req.getRequestURI()).thenReturn("/api/v1" + subPath);
        when(req.getContextPath()).thenReturn("");
        when(req.getServletPath()).thenReturn("/api/v1");
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
        HttpServletRequest req = mockRequest("GET", "/repos/acme/widgets/issues", "", context);
        HttpServletResponse resp = mockResponse(new ByteArrayOutputStream());
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        verify(chain).doFilter(any(), eq(resp));
        assertNull(context.getMutationField());
    }

    @Test
    void unallowlistedPath_returns403_denies() throws Exception {
        var context = new ScmApiRequestContext();
        HttpServletRequest req = mockRequest("POST", "/repos/acme/widgets/labels", "{}", context);
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
        HttpServletRequest req = mockRequest("POST", "/repos/acme/widgets/issues", "{}", context);
        HttpServletResponse resp = mockResponse(new ByteArrayOutputStream());
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        verify(resp).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verifyNoInteractions(chain);
    }

    @Test
    void allowlistedMutation_notPermitted_returns403() throws Exception {
        when(repoPermissionService.isAllowedToPropose("alice", "gitea", "/acme/widgets"))
                .thenReturn(false);
        var context = new ScmApiRequestContext();
        context.setResolvedUser("alice");
        HttpServletRequest req = mockRequest("POST", "/repos/acme/widgets/issues", "{}", context);
        HttpServletResponse resp = mockResponse(new ByteArrayOutputStream());
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        verify(resp).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verifyNoInteractions(chain);
    }

    @Test
    void allChecksPass_continuesChain_populatesContext() throws Exception {
        when(repoPermissionService.isAllowedToPropose("alice", "gitea", "/acme/widgets"))
                .thenReturn(true);
        var context = new ScmApiRequestContext();
        context.setResolvedUser("alice");
        HttpServletRequest req = mockRequest("POST", "/repos/acme/widgets/issues", "{}", context);
        HttpServletResponse resp = mockResponse(new ByteArrayOutputStream());
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        verify(chain).doFilter(any(), eq(resp));
        assertEquals("issues.create", context.getMutationField());
        assertEquals("acme", context.getRepoOwner());
        assertEquals("widgets", context.getRepoName());
    }
}
