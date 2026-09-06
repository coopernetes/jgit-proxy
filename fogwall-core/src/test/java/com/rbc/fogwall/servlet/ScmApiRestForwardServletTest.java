package com.rbc.fogwall.servlet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the forwarding servlet against a local stub upstream.
 *
 * <p>The method coverage matters more than it looks: {@code HttpServlet} has no {@code doPatch} and answers 405 for
 * one, so a PATCH was refused by the forwarder even though the Gitea allowlist admits it — {@code tea pr close} and
 * every issue/PR edit on that dialect.
 */
class ScmApiRestForwardServletTest {

    private HttpServer upstream;
    private final List<String> received = new ArrayList<>();
    private final List<String> receivedBodies = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/api/v1", exchange -> {
            received.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
    }

    @AfterEach
    void tearDown() {
        upstream.stop(0);
    }

    private ScmApiRestForwardServlet servlet() {
        return new ScmApiRestForwardServlet(
                "http://localhost:" + upstream.getAddress().getPort() + "/api/v1",
                ScmApiRestPathPolicy.EncodedSeparators.FORGEJO_FILE_PATH);
    }

    /**
     * Wrapped, as a gate filter leaves it in production. The servlet requires the wrapper — an unwrapped request means
     * the chain that authorizes it is missing — so an unwrapped mock would exercise a shape that cannot occur.
     */
    private static HttpServletRequest request(String method, String subPath) throws IOException {
        return request(method, subPath, "");
    }

    private static HttpServletRequest request(String method, String subPath, String body) throws IOException {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn(method);
        when(req.getRequestURI()).thenReturn("/api/v1" + subPath);
        when(req.getContextPath()).thenReturn("");
        when(req.getServletPath()).thenReturn("/api/v1");
        when(req.getProtocol()).thenReturn("HTTP/1.1");
        when(req.getInputStream()).thenReturn(bodyStream(body));
        return new RequestBodyWrapper(req);
    }

    private static ServletInputStream bodyStream(String body) {
        var source = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        return new ServletInputStream() {
            @Override
            public int read() {
                return source.read();
            }

            @Override
            public boolean isFinished() {
                return source.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {}
        };
    }

    private static HttpServletResponse response(ByteArrayOutputStream sink) throws IOException {
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(resp.getOutputStream()).thenReturn(new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {}

            @Override
            public void write(int b) {
                sink.write(b);
            }
        });
        return resp;
    }

    /**
     * The wrapper is what bounds the read, so its absence means a gate filter was unregistered or reordered. Failing is
     * the point: a raw read here would keep serving traffic while the chain that authorizes it is missing.
     */
    @Test
    void unwrappedRequest_fails() throws Exception {
        HttpServletRequest raw = mock(HttpServletRequest.class);
        when(raw.getMethod()).thenReturn("POST");
        when(raw.getRequestURI()).thenReturn("/api/v1/repos/acme/widgets/pulls");
        when(raw.getContextPath()).thenReturn("");
        when(raw.getServletPath()).thenReturn("/api/v1");
        when(raw.getProtocol()).thenReturn("HTTP/1.1");
        when(raw.getInputStream()).thenReturn(bodyStream(""));

        var resp = response(new ByteArrayOutputStream());
        assertThrows(IllegalStateException.class, () -> servlet().service(raw, resp));
        assertTrue(received.isEmpty(), "nothing reaches the upstream");
    }

    /** The regression: PATCH reached the servlet and was answered 405 instead of forwarded. */
    @Test
    void forwardsPatch() throws Exception {
        var req = request("PATCH", "/repos/acme/widgets/pulls/1");
        var out = new ByteArrayOutputStream();
        var resp = response(out);

        servlet().service(req, resp);

        assertEquals(List.of("PATCH /api/v1/repos/acme/widgets/pulls/1"), received);
        verify(resp).setStatus(200);
    }

    /**
     * DELETE carries a body on this dialect — {@code tea issue edit --remove-assignees} puts the logins to drop there —
     * so forwarding the method without the entity would reach the upstream as a request to remove nobody.
     */
    @Test
    void forwardsDeleteWithItsBody() throws Exception {
        var req = request("DELETE", "/repos/acme/widgets/issues/1/assignees", "{\"assignees\":[\"someone\"]}");
        var resp = response(new ByteArrayOutputStream());

        servlet().service(req, resp);

        assertEquals(List.of("DELETE /api/v1/repos/acme/widgets/issues/1/assignees"), received);
        assertEquals(List.of("{\"assignees\":[\"someone\"]}"), receivedBodies);
        verify(resp).setStatus(200);
    }

    @Test
    void stillForwardsTheMethodsHttpServletHandlesItself() throws Exception {
        servlet().service(request("GET", "/repos/acme/widgets/pulls"), response(new ByteArrayOutputStream()));
        assertEquals(List.of("GET /api/v1/repos/acme/widgets/pulls"), received);
    }

    /** A path the policy refuses is answered locally, without any upstream call. */
    @Test
    void refusesADisallowedPathWithoutReachingUpstream() throws Exception {
        var out = new ByteArrayOutputStream();
        var resp = response(out);

        servlet().service(request("PATCH", "/repos/acme%2Fwidgets/pulls/1"), resp);

        assertTrue(received.isEmpty(), "must not reach upstream: " + received);
        verify(resp).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    }
}
