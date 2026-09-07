package com.rbc.fogwall.jetty;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.ee11.servlet.FilterHolder;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link BlockingContentHandler} ensures the full request body is available to servlets even when the
 * body arrives in multiple TCP segments with a pause between them.
 *
 * <p>Uses a raw {@link Socket} to send the HTTP headers and first chunk of the body, pauses, then sends the rest. This
 * simulates what happens over a real network with large git packs — Jetty's EPC model would normally dispatch the
 * servlet on its reserved thread before the body has fully arrived, causing {@code readAllBytes()} to return truncated
 * data.
 */
class BlockingContentHandlerTest {

    private Server server;
    private int port;
    private final AtomicInteger bytesRead = new AtomicInteger(-1);
    private final AtomicReference<Throwable> servletError = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = new Server();
        var connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        var context = new ServletContextHandler("/", false, false);

        // Filter that reads the full body via readAllBytes() — mirrors RequestBodyWrapper
        context.addFilter(
                new FilterHolder(new BodyReadingFilter(bytesRead, servletError)),
                "/*",
                EnumSet.of(jakarta.servlet.DispatcherType.REQUEST));

        context.addServlet(new ServletHolder(new OkServlet()), "/*");

        server.setHandler(new BlockingContentHandler(context));
        server.start();
        port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    /**
     * A second server whose connectors are named the way the applications name them, so the handler can tell an SCM API
     * port from a git one.
     */
    private Server namedServer;

    private int mainPort;
    private int scmApiPort;

    private void startNamedServer() throws Exception {
        namedServer = new Server();
        var main = new ServerConnector(namedServer);
        main.setPort(0);
        main.setName(FogwallServletRegistrar.MAIN_HTTP_CONNECTOR);
        namedServer.addConnector(main);

        var scmApi = new ServerConnector(namedServer);
        scmApi.setPort(0);
        scmApi.setName(FogwallServletRegistrar.SCM_API_CONNECTOR_PREFIX + "github");
        namedServer.addConnector(scmApi);

        var context = new ServletContextHandler("/", false, false);
        context.addFilter(
                new FilterHolder(new BodyReadingFilter(bytesRead, servletError)),
                "/*",
                EnumSet.of(jakarta.servlet.DispatcherType.REQUEST));
        context.addServlet(new ServletHolder(new OkServlet()), "/*");
        namedServer.setHandler(new BlockingContentHandler(context));
        namedServer.start();
        mainPort = main.getLocalPort();
        scmApiPort = scmApi.getLocalPort();
    }

    private static String postRaw(int port, int bodySize) throws Exception {
        byte[] body = new byte[bodySize];
        String headers = "POST /test HTTP/1.1\r\n"
                + "Host: localhost:" + port + "\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "Content-Length: " + bodySize + "\r\n"
                + "\r\n";
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(headers.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
            byte[] buf = new byte[4096];
            int n = socket.getInputStream().read(buf);
            return new String(buf, 0, n, StandardCharsets.US_ASCII);
        }
    }

    /**
     * This handler runs before any context, so what it buffers is read before authentication. The proposals ports do
     * their own bounded read in the gate filter, and nothing on them needs the chunked-push workaround the handler
     * exists for, so a request arriving on one is passed straight through.
     *
     * <p>The risk of passing through is that the servlet layer stops seeing a complete body — the very thing this
     * handler was added to guarantee — so both paths are checked for the whole body, not just for a status.
     */
    @Test
    void scmApiConnector_isPassedThroughWithTheBodyStillComplete() throws Exception {
        startNamedServer();
        try {
            bytesRead.set(-1);
            assertTrue(postRaw(mainPort, 8192).startsWith("HTTP/1.1 200"), "git ports are buffered as before");
            assertEquals(8192, bytesRead.get(), "the buffered path delivers the whole body");

            bytesRead.set(-1);
            assertTrue(postRaw(scmApiPort, 8192).startsWith("HTTP/1.1 200"), "proposals ports pass through");
            assertEquals(8192, bytesRead.get(), "and the unbuffered path delivers it too");
        } finally {
            namedServer.stop();
        }
    }

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) server.stop();
    }

    @Test
    void splitBody_fullBodyAvailable() throws Exception {
        int totalBodySize = 200_000;
        int firstChunkSize = 1_000;
        byte[] body = new byte[totalBodySize];
        for (int i = 0; i < totalBodySize; i++) {
            body[i] = (byte) ('A' + (i % 26));
        }

        String headers = "POST /test HTTP/1.1\r\n"
                + "Host: localhost:" + port + "\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "Content-Length: " + totalBodySize + "\r\n"
                + "\r\n";

        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();

            // Send headers + first chunk
            out.write(headers.getBytes(StandardCharsets.US_ASCII));
            out.write(body, 0, firstChunkSize);
            out.flush();

            // Pause — simulates network segmentation / TCP buffering
            Thread.sleep(200);

            // Send remaining body
            out.write(body, firstChunkSize, totalBodySize - firstChunkSize);
            out.flush();

            // Read response
            InputStream in = socket.getInputStream();
            byte[] responseBuf = new byte[4096];
            int n = in.read(responseBuf);
            String response = new String(responseBuf, 0, n, StandardCharsets.US_ASCII);

            assertTrue(response.startsWith("HTTP/1.1 200"), "Expected 200 OK but got: " + response);
        }

        assertNull(servletError.get(), "Servlet threw an exception: " + servletError.get());
        assertEquals(totalBodySize, bytesRead.get(), "Filter should have read the entire body");
    }

    @Test
    void normalRequest_stillWorks() throws Exception {
        byte[] body = "hello world".getBytes(StandardCharsets.UTF_8);
        String headers = "POST /test HTTP/1.1\r\n"
                + "Host: localhost:" + port + "\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "\r\n";

        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(headers.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();

            InputStream in = socket.getInputStream();
            byte[] responseBuf = new byte[4096];
            int n = in.read(responseBuf);
            String response = new String(responseBuf, 0, n, StandardCharsets.US_ASCII);

            assertTrue(response.startsWith("HTTP/1.1 200"), "Expected 200 OK but got: " + response);
        }

        assertNull(servletError.get());
        assertEquals(body.length, bytesRead.get());
    }

    /** Filter that reads the entire request body and records how many bytes were received. */
    private static class BodyReadingFilter implements jakarta.servlet.Filter {

        private final AtomicInteger bytesRead;
        private final AtomicReference<Throwable> error;

        BodyReadingFilter(AtomicInteger bytesRead, AtomicReference<Throwable> error) {
            this.bytesRead = bytesRead;
            this.error = error;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            try {
                byte[] body = ((HttpServletRequest) request).getInputStream().readAllBytes();
                bytesRead.set(body.length);
            } catch (Exception e) {
                error.set(e);
            }
            chain.doFilter(request, response);
        }
    }

    private static class OkServlet extends HttpServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setStatus(200);
            resp.getWriter().write("OK");
        }
    }
}
