package org.finos.gitproxy.dashboard.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ConnectivityErrorClassifierTest {

    @Nested
    class ClassifyTcpError {

        @Test
        void socketTimeoutException_returnsTimeout() {
            assertEquals(
                    "TIMEOUT", ConnectivityErrorClassifier.classifyTcpError(new SocketTimeoutException("timed out")));
        }

        @Test
        void connectException_withRefusedMessage_returnsRefused() {
            assertEquals(
                    "REFUSED",
                    ConnectivityErrorClassifier.classifyTcpError(new ConnectException("Connection refused")));
        }

        @Test
        void connectException_withResetMessage_returnsReset() {
            assertEquals(
                    "RESET", ConnectivityErrorClassifier.classifyTcpError(new ConnectException("Connection reset")));
        }

        @Test
        void connectException_withBlankMessage_returnsRefused() {
            // ConnectException with no useful message still defaults to REFUSED (port closed)
            assertEquals("REFUSED", ConnectivityErrorClassifier.classifyTcpError(new ConnectException()));
        }

        @Test
        void socketException_withResetMessage_returnsReset() {
            assertEquals(
                    "RESET", ConnectivityErrorClassifier.classifyTcpError(new SocketException("Connection reset")));
        }

        @Test
        void socketException_withRefusedMessage_returnsRefused() {
            assertEquals(
                    "REFUSED", ConnectivityErrorClassifier.classifyTcpError(new SocketException("Connection refused")));
        }

        @Test
        void socketException_withTimedOutMessage_returnsTimeout() {
            assertEquals(
                    "TIMEOUT",
                    ConnectivityErrorClassifier.classifyTcpError(new SocketException("Connection timed out")));
        }

        @Test
        void socketException_withTimeoutMessage_returnsTimeout() {
            assertEquals("TIMEOUT", ConnectivityErrorClassifier.classifyTcpError(new SocketException("Read timeout")));
        }

        @Test
        void unrecognisedException_returnsError() {
            assertEquals("ERROR", ConnectivityErrorClassifier.classifyTcpError(new IOException("Network unreachable")));
        }

        @Test
        void nullMessage_doesNotThrow() {
            assertEquals("REFUSED", ConnectivityErrorClassifier.classifyTcpError(new ConnectException((String) null)));
        }
    }

    @Nested
    class ClassifyNetworkError {

        @Test
        void directSocketTimeout_returnsTimeout() {
            assertEquals("TIMEOUT", ConnectivityErrorClassifier.classifyNetworkError(new SocketTimeoutException()));
        }

        @Test
        void wrappedSocketTimeout_unwrapsAndReturnsTimeout() {
            // HttpClient wraps the real cause in an outer IOException
            IOException wrapper =
                    new IOException("HTTP request failed", new SocketTimeoutException("connect timed out"));
            assertEquals("TIMEOUT", ConnectivityErrorClassifier.classifyNetworkError(wrapper));
        }

        @Test
        void wrappedConnectionReset_unwrapsAndReturnsReset() {
            IOException wrapper = new IOException("HTTP request failed", new SocketException("Connection reset"));
            assertEquals("RESET", ConnectivityErrorClassifier.classifyNetworkError(wrapper));
        }

        @Test
        void wrappedRefused_unwrapsAndReturnsRefused() {
            IOException wrapper = new IOException("HTTP request failed", new ConnectException("Connection refused"));
            assertEquals("REFUSED", ConnectivityErrorClassifier.classifyNetworkError(wrapper));
        }

        @Test
        void noCause_andUnrecognised_returnsError() {
            assertEquals("ERROR", ConnectivityErrorClassifier.classifyNetworkError(new IOException("something else")));
        }

        @Test
        void wrappedUnrecognised_returnsError() {
            IOException wrapper = new IOException("outer", new IOException("inner unknown"));
            assertEquals("ERROR", ConnectivityErrorClassifier.classifyNetworkError(wrapper));
        }
    }

    @Nested
    class ClassifyTlsError {

        @Test
        void pkixPathBuilding_returnsCertInvalid() {
            assertEquals(
                    "TLS_CERT_INVALID",
                    ConnectivityErrorClassifier.classifyTlsError(new SSLHandshakeException(
                            "PKIX path building failed: unable to find valid certification path")));
        }

        @Test
        void certificateKeyword_returnsCertInvalid() {
            assertEquals(
                    "TLS_CERT_INVALID",
                    ConnectivityErrorClassifier.classifyTlsError(new SSLHandshakeException("Certificate expired")));
        }

        @Test
        void certKeyword_returnsCertInvalid() {
            assertEquals(
                    "TLS_CERT_INVALID",
                    ConnectivityErrorClassifier.classifyTlsError(
                            new SSLHandshakeException("self-signed cert in chain")));
        }

        @Test
        void trustAnchorKeyword_returnsCertInvalid() {
            assertEquals(
                    "TLS_CERT_INVALID",
                    ConnectivityErrorClassifier.classifyTlsError(
                            new SSLHandshakeException("No trust anchor found for issuer")));
        }

        @Test
        void trustanchorOneWord_returnsCertInvalid() {
            assertEquals(
                    "TLS_CERT_INVALID",
                    ConnectivityErrorClassifier.classifyTlsError(
                            new SSLHandshakeException("trustanchor for certpath not found")));
        }

        @Test
        void generalAlertFromPeer_returnsHandshakeFailed() {
            assertEquals(
                    "TLS_HANDSHAKE_FAILED",
                    ConnectivityErrorClassifier.classifyTlsError(
                            new SSLHandshakeException("Received fatal alert: handshake_failure")));
        }

        @Test
        void protocolVersionMismatch_returnsHandshakeFailed() {
            assertEquals(
                    "TLS_HANDSHAKE_FAILED",
                    ConnectivityErrorClassifier.classifyTlsError(new SSLHandshakeException("No appropriate protocol")));
        }
    }
}
