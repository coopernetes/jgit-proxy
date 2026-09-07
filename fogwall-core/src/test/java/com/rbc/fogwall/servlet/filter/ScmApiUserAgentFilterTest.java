package com.rbc.fogwall.servlet.filter;

import static com.rbc.fogwall.servlet.ScmApiRequestContext.SCM_API_REQUEST_ATTR;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rbc.fogwall.db.model.ScmApiActionStatus;
import com.rbc.fogwall.scmapi.ScmApiClientType;
import com.rbc.fogwall.servlet.ScmApiRequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

class ScmApiUserAgentFilterTest {

    private static HttpServletRequest request(String userAgent, ScmApiRequestContext context) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("User-Agent")).thenReturn(userAgent);
        when(req.getAttribute(SCM_API_REQUEST_ATTR)).thenReturn(context);
        return req;
    }

    private static HttpServletResponse response(ByteArrayOutputStream body) throws Exception {
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
    void recordsTheUserAgentAndClientTypeForAudit() throws Exception {
        var context = new ScmApiRequestContext();
        var chain = mock(FilterChain.class);

        new ScmApiUserAgentFilter(false)
                .doFilter(
                        request("tea/0.15.1 (linux/amd64) go-sdk/v1.2.0", context),
                        response(new ByteArrayOutputStream()),
                        chain);

        assertEquals("tea/0.15.1 (linux/amd64) go-sdk/v1.2.0", context.getUserAgent());
        assertEquals(ScmApiClientType.TEA_CLI, context.getClientType());
        verify(chain).doFilter(any(), any());
    }

    @Test
    void disabledByDefault_passesAnyClientThrough() throws Exception {
        var context = new ScmApiRequestContext();
        var chain = mock(FilterChain.class);

        new ScmApiUserAgentFilter(false)
                .doFilter(request("curl/8.9.1", context), response(new ByteArrayOutputStream()), chain);

        verify(chain).doFilter(any(), any());
        assertEquals(ScmApiClientType.UNKNOWN, context.getClientType());
        assertNull(context.getStatus(), "a pass-through must not stamp a denial on the audit record");
    }

    @Test
    void whenRequired_knownCliIsAllowed() throws Exception {
        var context = new ScmApiRequestContext();
        var chain = mock(FilterChain.class);

        new ScmApiUserAgentFilter(true)
                .doFilter(request("GitHub CLI 2.98.0", context), response(new ByteArrayOutputStream()), chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void whenRequired_browserIsDeniedAndAudited() throws Exception {
        var context = new ScmApiRequestContext();
        var chain = mock(FilterChain.class);
        var resp = response(new ByteArrayOutputStream());

        new ScmApiUserAgentFilter(true).doFilter(request("Mozilla/5.0 (X11; Linux x86_64)", context), resp, chain);

        verify(resp).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verifyNoInteractions(chain);
        assertEquals(ScmApiActionStatus.DENIED, context.getStatus());
        assertEquals(ScmApiClientType.BROWSER, context.getClientType());
    }

    @Test
    void whenRequired_missingHeaderIsDenied() throws Exception {
        var context = new ScmApiRequestContext();
        var chain = mock(FilterChain.class);
        var resp = response(new ByteArrayOutputStream());

        new ScmApiUserAgentFilter(true).doFilter(request(null, context), resp, chain);

        verify(resp).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verifyNoInteractions(chain);
    }

    /**
     * The filter must be strictly subtractive. Turning it on can only ever deny more than leaving it off — never fewer,
     * and never differently. If this ever fails, {@code User-Agent} has become an authorization input and a forged
     * header would buy real access.
     */
    @Test
    void enablingItOnlyEverDeniesMore_neverAllowsMore() throws Exception {
        for (String ua : new String[] {
            "GitHub CLI 2.98.0",
            "glab/v1.116.0",
            "tea/0.15.1",
            "forgejo-cli/0.6.0",
            "Mozilla/5.0 (X11; Linux x86_64)",
            "curl/8.9.1",
            null,
            ""
        }) {
            var offChain = mock(FilterChain.class);
            new ScmApiUserAgentFilter(false)
                    .doFilter(request(ua, new ScmApiRequestContext()), response(new ByteArrayOutputStream()), offChain);
            verify(offChain, description("filter disabled must always pass through, ua=" + ua))
                    .doFilter(any(), any());

            var onChain = mock(FilterChain.class);
            new ScmApiUserAgentFilter(true)
                    .doFilter(request(ua, new ScmApiRequestContext()), response(new ByteArrayOutputStream()), onChain);
            boolean allowedWhenOn = mockingDetails(onChain).getInvocations().stream()
                    .anyMatch(i -> i.getMethod().getName().equals("doFilter"));

            assertEquals(
                    ScmApiClientType.classify(ua).isKnownCli(),
                    allowedWhenOn,
                    "enabled filter must allow exactly the known CLIs and nothing more, ua=" + ua);
        }
    }
}
