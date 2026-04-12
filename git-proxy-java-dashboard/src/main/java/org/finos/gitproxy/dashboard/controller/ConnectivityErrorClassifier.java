package org.finos.gitproxy.dashboard.controller;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLHandshakeException;

/**
 * Pure-function error classification for the connectivity check. Extracted here so the logic can be unit-tested
 * independently of the network I/O in {@link ConnectivityController}.
 */
class ConnectivityErrorClassifier {

    private ConnectivityErrorClassifier() {}

    /**
     * Classifies a TCP-layer exception into a short error code that maps to OS-level error signals:
     *
     * <ul>
     *   <li>{@code TIMEOUT} — firewall DROP or host unreachable (no RST received within timeout)
     *   <li>{@code REFUSED} — firewall REJECT or no listener on port (RST received immediately)
     *   <li>{@code RESET} — connection established then RST mid-stream (proxy intercept, load balancer)
     *   <li>{@code ERROR} — other (DNS failure, network unreachable, etc.)
     * </ul>
     */
    static String classifyTcpError(Exception e) {
        if (e instanceof SocketTimeoutException) return "TIMEOUT";
        if (e instanceof ConnectException) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("refused")) return "REFUSED";
            if (msg.contains("reset")) return "RESET";
            return "REFUSED"; // ConnectException default
        }
        if (e instanceof SocketException) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("reset")) return "RESET";
            if (msg.contains("refused")) return "REFUSED";
            if (msg.contains("timeout") || msg.contains("timed out")) return "TIMEOUT";
        }
        return "ERROR";
    }

    /**
     * Like {@link #classifyTcpError} but also unwraps one level of cause, since {@code HttpClient} wraps
     * {@code IOException} in an outer {@code IOException}.
     */
    static String classifyNetworkError(Exception e) {
        String result = classifyTcpError(e);
        if (!"ERROR".equals(result)) return result;
        if (e.getCause() instanceof Exception cause) {
            result = classifyTcpError(cause);
        }
        return result;
    }

    /**
     * Classifies a TLS-layer exception.
     *
     * <ul>
     *   <li>{@code TLS_CERT_INVALID} — PKIX path building or certificate chain failure
     *   <li>{@code TLS_HANDSHAKE_FAILED} — general handshake failure (alert from peer)
     * </ul>
     */
    static String classifyTlsError(SSLHandshakeException e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("pkix")
                || msg.contains("certificate")
                || msg.contains("cert")
                || msg.contains("trustanchor")
                || msg.contains("trust anchor")) {
            return "TLS_CERT_INVALID";
        }
        return "TLS_HANDSHAKE_FAILED";
    }
}
