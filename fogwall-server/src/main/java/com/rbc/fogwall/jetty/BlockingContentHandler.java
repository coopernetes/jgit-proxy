package com.rbc.fogwall.jetty;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * Handler wrapper that reads the full HTTP request body using Jetty 12's native {@link Content.Source} API before
 * dispatching to the servlet layer.
 *
 * <p>Many reverse proxies do not faithfully forward {@code Transfer-Encoding: chunked} requests, which git uses for
 * pushes exceeding {@code http.postBuffer} (default 1 MiB). This handler reads the complete body at the core Handler
 * level using the {@link Content.Source#read()} / {@link Content.Source#demand} cycle, then wraps the request with a
 * pre-buffered {@link Content.Source} so both servlet filters and JGit's {@code ReceivePack} read from memory.
 *
 * <p>GET requests pass through without buffering, as do the proposals listeners — their gate filters buffer for
 * themselves against a bound, and a JSON request from a CLI declares its length.
 *
 * <p>The read is <b>not</b> bounded. This handler runs before any context, so before authentication and before
 * {@code server.max-push-bytes}, which {@code ParseGitRequestFilter} enforces once the body is already in heap: that
 * check reports the right error while having allocated whatever was sent. A bound at this layer could only answer with
 * a bare HTTP status, where git renders an explanation solely from an {@code ERR} pkt-line on a 200, and a refusal
 * issued while the client is still uploading is not reliably delivered at all. Bounding the allocation needs the body
 * streamed rather than buffered.
 *
 * @see <a href="docs/internals/GIT_INTERNALS.md">GIT_INTERNALS.md — "Large pushes and chunked transfer encoding"</a>
 */
@Slf4j
public class BlockingContentHandler extends Handler.Wrapper {

    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);
    private static final int DEMAND_TIMEOUT_SECONDS = 30;

    public BlockingContentHandler(Handler wrapped) {
        super(wrapped);
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return super.handle(request, response, callback);
        }

        // The proposals listeners buffer for themselves, bounded, in their gate filters. Pre-buffering here would
        // read the body before authentication and hold up to this handler's limit for a caller who turns out not to
        // be anyone. Nothing on those ports needs the chunked-encoding workaround either: this handler exists for
        // reverse proxies that mangle a chunked git push, and a JSON request from a CLI declares its length.
        if (isScmApiConnector(request)) {
            return super.handle(request, response, callback);
        }

        request.getContext().execute(() -> {
            try {
                byte[] body = readAllContent(request);
                log.debug("BlockingContentHandler: buffered {} bytes for {}", body.length, request.getHttpURI());

                Request buffered = new BufferedBodyRequest(request, body);
                if (!getHandler().handle(buffered, response, callback)) {
                    Response.writeError(buffered, response, callback, 404);
                }
            } catch (Exception e) {
                log.error("BlockingContentHandler: failed to buffer request body", e);
                callback.failed(e);
            }
        });
        return true;
    }

    private static boolean isScmApiConnector(Request request) {
        String name = request.getConnectionMetaData().getConnector().getName();
        return name != null && name.startsWith(FogwallServletRegistrar.SCM_API_CONNECTOR_PREFIX);
    }

    private static byte[] readAllContent(Request request) throws Exception {
        var baos = new ByteArrayOutputStream();
        int chunkCount = 0;

        while (true) {
            Content.Chunk chunk = request.read();

            if (chunk == null) {
                var latch = new CountDownLatch(1);
                request.demand(latch::countDown);
                if (!latch.await(DEMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    log.error(
                            "BlockingContentHandler: demand timed out after {}s, read {} bytes so far in {} chunks",
                            DEMAND_TIMEOUT_SECONDS,
                            baos.size(),
                            chunkCount);
                    break;
                }
                continue;
            }

            if (Content.Chunk.isFailure(chunk)) {
                log.error(
                        "BlockingContentHandler: content source failure after {} bytes in {} chunks",
                        baos.size(),
                        chunkCount,
                        chunk.getFailure());
                break;
            }

            ByteBuffer buf = chunk.getByteBuffer();
            int remaining = buf.remaining();
            chunkCount++;

            if (remaining > 0) {
                byte[] data = new byte[remaining];
                buf.get(data);
                baos.write(data);
            }
            chunk.release();

            if (chunk.isLast()) {
                break;
            }
        }

        return baos.toByteArray();
    }

    /**
     * Request wrapper that provides a pre-buffered body as the {@link Content.Source}. The servlet layer's
     * {@code HttpInput} reads from this instead of the network, so the full body is always available immediately.
     */
    private static class BufferedBodyRequest extends Request.Wrapper {
        private final ByteBuffer buffer;
        private boolean consumed;

        BufferedBodyRequest(Request wrapped, byte[] body) {
            super(wrapped);
            this.buffer = ByteBuffer.wrap(body).asReadOnlyBuffer();
        }

        @Override
        public Content.Chunk read() {
            if (consumed) {
                return Content.Chunk.from(EMPTY, true);
            }
            consumed = true;
            return Content.Chunk.from(buffer.slice(), true);
        }

        @Override
        public void demand(Runnable demandCallback) {
            demandCallback.run();
        }

        @Override
        public void fail(Throwable failure) {}

        @Override
        public void fail(Throwable failure, boolean last) {}
    }
}
