package org.finos.gitproxy.git;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.transport.ReceivePack;

/**
 * Sends periodic keepalive dots on sideband-2 to prevent idle-timeout disconnects during long-running validation steps
 * (e.g. secret scanning, approval polling).
 *
 * <p>A single background daemon thread fires every {@code interval} seconds and writes a {@code "."} progress message.
 * The dot is harmless whitespace and does not affect validation output. If the interval is zero or negative the sender
 * is a no-op.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * try (HeartbeatSender hb = new HeartbeatSender(rp, Duration.ofSeconds(10))) {
 *     hb.start();
 *     // ... long-running hook chain ...
 * }
 * }</pre>
 *
 * <p><b>Thread safety:</b> The heartbeat writes on a background thread while hooks write on the request thread. JGit's
 * sideband stream is not thread-safe, so a very small race window exists. In practice this is benign because the
 * heartbeat is only needed during long silent gaps (subprocess waits, polling loops) when hooks are not actively
 * writing.
 */
@Slf4j
public class HeartbeatSender implements AutoCloseable {

    private final ReceivePack rp;
    private final Duration interval;
    private ScheduledExecutorService executor;

    public HeartbeatSender(ReceivePack rp, Duration interval) {
        this.rp = rp;
        this.interval = interval;
    }

    /** Starts the heartbeat background thread. No-op if interval is zero or negative. */
    public void start() {
        if (interval.isZero() || interval.isNegative()) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jgit-proxy-heartbeat");
            t.setDaemon(true);
            return t;
        });
        long seconds = interval.toSeconds();
        executor.scheduleAtFixedRate(this::sendDot, seconds, seconds, TimeUnit.SECONDS);
        log.debug("Heartbeat started (interval: {}s)", seconds);
    }

    private void sendDot() {
        try {
            rp.sendMessage(".");
            rp.getMessageOutputStream().flush();
        } catch (Exception e) {
            // Session may have ended; stop firing
            if (executor != null) {
                executor.shutdownNow();
            }
        }
    }

    @Override
    public void close() {
        if (executor != null) {
            executor.shutdownNow();
            log.debug("Heartbeat stopped");
        }
    }
}
