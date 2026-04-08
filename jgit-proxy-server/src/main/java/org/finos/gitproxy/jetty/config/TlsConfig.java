package org.finos.gitproxy.jetty.config;

import lombok.Data;

/**
 * Binds the {@code server.tls:} block in git-proxy.yml.
 *
 * <p>Two independent concerns live here:
 *
 * <ul>
 *   <li><b>Server listener TLS</b> — expose the proxy over HTTPS. Configure either PEM cert+key (preferred; no keytool
 *       needed) or a PKCS12/JKS keystore (for shops that already manage one). When neither is set, no HTTPS listener is
 *       started and plain HTTP is used.
 *   <li><b>Upstream trust</b> — trust a custom CA bundle when making outbound HTTPS connections to upstream providers.
 *       Merges with the JVM's built-in trust anchors so public hosts (GitHub, GitLab) still work.
 * </ul>
 *
 * <p>Example (PEM-based server + custom upstream CA):
 *
 * <pre>{@code
 * server:
 *   tls:
 *     port: 8443
 *     certificate: /etc/gitproxy/tls/server.pem
 *     key: /etc/gitproxy/tls/server-key.pem   # PKCS8; convert with: openssl pkcs8 -topk8 -nocrypt
 *     trust-ca-bundle: /etc/gitproxy/tls/internal-ca.pem
 * }</pre>
 *
 * <p>Example (keystore-based server):
 *
 * <pre>{@code
 * server:
 *   tls:
 *     port: 8443
 *     keystore:
 *       path: /etc/gitproxy/tls/keystore.p12
 *       password: changeit
 *       type: PKCS12
 * }</pre>
 */
@Data
public class TlsConfig {

    /** HTTPS listener port. Only used when server TLS is configured. */
    private int port = 8443;

    /**
     * Path to a PEM-encoded X.509 certificate (or certificate chain). Used together with {@link #key}. Mutually
     * exclusive with {@link #keystore}.
     */
    private String certificate;

    /**
     * Path to a PEM-encoded PKCS8 private key (no passphrase). Generate with:
     *
     * <pre>
     * openssl pkcs8 -topk8 -nocrypt -in server.pem -out server-key.pem
     * </pre>
     *
     * Used together with {@link #certificate}.
     */
    private String key;

    /** Keystore-based server TLS configuration. Mutually exclusive with {@link #certificate}/{@link #key}. */
    private KeystoreConfig keystore;

    /**
     * Path to a PEM file containing one or more CA certificates to trust when making outbound HTTPS connections to
     * upstream providers. These are merged with the JVM's built-in trust anchors — public hosts continue to work
     * without modification.
     *
     * <p>This is the primary mechanism for trusting an enterprise internal PKI without keytool.
     */
    private String trustCaBundle;

    /** Returns true if enough server TLS config is present to start an HTTPS listener. */
    public boolean isServerTlsConfigured() {
        return (certificate != null && key != null) || keystore != null;
    }

    /** Returns true if a custom upstream CA bundle is configured. */
    public boolean isUpstreamTrustConfigured() {
        return trustCaBundle != null;
    }

    /** Binds the {@code server.tls.keystore:} block. */
    @Data
    public static class KeystoreConfig {
        private String path;
        private String password;
        /** Keystore type. Common values: {@code PKCS12} (default), {@code JKS}. */
        private String type = "PKCS12";
    }
}
