package org.finos.gitproxy.tls;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Utilities for building {@link SSLContext} instances from PEM files, without requiring keytool or JKS manipulation.
 */
public final class SslUtil {

    private SslUtil() {}

    /**
     * Builds an {@link UpstreamTls} containing an {@link SSLContext} and the underlying {@link TrustManager} array.
     * Both are needed: the {@code SSLContext} for Jetty's {@code HttpClient} (transparent proxy), and the
     * {@code TrustManager[]} for JGit's {@code HttpConnection.configure()} (store-and-forward).
     *
     * <p>The resulting context trusts the CAs in {@code caBundlePem} in addition to the JVM's built-in trust anchors.
     * Public hosts (GitHub, GitLab, Bitbucket) continue to work without modifying the JVM truststore.
     *
     * <p>The PEM file may contain one or more {@code -----BEGIN CERTIFICATE-----} blocks.
     *
     * @param caBundlePem path to a PEM file containing CA certificate(s)
     */
    public static UpstreamTls buildUpstreamTls(Path caBundlePem) throws GeneralSecurityException, IOException {
        X509TrustManager customTm = loadCustomTrustManager(caBundlePem);
        X509TrustManager systemTm = loadSystemTrustManager();
        var merged = new MergingTrustManager(customTm, systemTm);
        TrustManager[] trustManagers = new TrustManager[] {merged};
        var ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustManagers, null);
        return new UpstreamTls(ctx, trustManagers);
    }

    /** Holds both the {@link SSLContext} and underlying {@link TrustManager}s for upstream connections. */
    public record UpstreamTls(SSLContext sslContext, TrustManager[] trustManagers) {}

    /**
     * Builds a server-side {@link SSLContext} from a PEM certificate file and a PKCS8 private key file. The private key
     * must be unencrypted (no passphrase).
     *
     * <p>Convert a PKCS1 key to PKCS8 with:
     *
     * <pre>
     * openssl pkcs8 -topk8 -nocrypt -in server.pem -out server-key.pem
     * </pre>
     *
     * @param certPem path to a PEM-encoded X.509 certificate (or chain)
     * @param keyPem path to a PEM-encoded PKCS8 private key
     * @return an {@link SSLContext} for the Jetty server listener
     */
    public static SSLContext buildServerSslContext(Path certPem, Path keyPem)
            throws GeneralSecurityException, IOException {
        var cf = CertificateFactory.getInstance("X.509");
        List<X509Certificate> certs = new ArrayList<>();
        try (InputStream is = Files.newInputStream(certPem)) {
            for (var cert : cf.generateCertificates(is)) {
                certs.add((X509Certificate) cert);
            }
        }
        if (certs.isEmpty()) {
            throw new IllegalArgumentException("No certificates found in " + certPem);
        }

        var privateKey = loadPkcs8PrivateKey(keyPem, certs.get(0));

        var keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("server", privateKey, new char[0], certs.toArray(new java.security.cert.Certificate[0]));

        var kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, new char[0]);

        var ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    // --- private helpers ---

    private static X509TrustManager loadCustomTrustManager(Path caBundlePem)
            throws GeneralSecurityException, IOException {
        var cf = CertificateFactory.getInstance("X.509");
        var ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        int i = 0;
        try (InputStream is = Files.newInputStream(caBundlePem)) {
            for (var cert : cf.generateCertificates(is)) {
                ks.setCertificateEntry("custom-ca-" + i++, cert);
            }
        }
        if (i == 0) {
            throw new IllegalArgumentException("No CA certificates found in " + caBundlePem);
        }
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        return getX509TrustManager(tmf);
    }

    private static X509TrustManager loadSystemTrustManager() throws GeneralSecurityException {
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null); // null = JVM default truststore
        return getX509TrustManager(tmf);
    }

    private static X509TrustManager getX509TrustManager(TrustManagerFactory tmf) {
        for (var tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager x509tm) {
                return x509tm;
            }
        }
        throw new IllegalStateException("No X509TrustManager found in TrustManagerFactory");
    }

    private static java.security.PrivateKey loadPkcs8PrivateKey(Path keyPem, X509Certificate cert)
            throws GeneralSecurityException, IOException {
        String pem = Files.readString(keyPem);
        String stripped = pem.replaceAll("-----BEGIN .*?-----", "")
                .replaceAll("-----END .*?-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(stripped);
        var spec = new java.security.spec.PKCS8EncodedKeySpec(der);
        // Determine algorithm from the cert's public key
        String algorithm = cert.getPublicKey().getAlgorithm();
        return java.security.KeyFactory.getInstance(algorithm).generatePrivate(spec);
    }

    /**
     * An {@link X509TrustManager} that tries the custom trust manager first, then falls back to the system trust
     * manager. This allows custom CA certs to be used alongside the JVM's built-in trust anchors without merging
     * KeyStores.
     */
    private static final class MergingTrustManager implements X509TrustManager {

        private final X509TrustManager custom;
        private final X509TrustManager system;

        MergingTrustManager(X509TrustManager custom, X509TrustManager system) {
            this.custom = custom;
            this.system = system;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                custom.checkClientTrusted(chain, authType);
            } catch (CertificateException e) {
                system.checkClientTrusted(chain, authType);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                custom.checkServerTrusted(chain, authType);
            } catch (CertificateException e) {
                system.checkServerTrusted(chain, authType);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            var customIssuers = custom.getAcceptedIssuers();
            var systemIssuers = system.getAcceptedIssuers();
            var combined = new X509Certificate[customIssuers.length + systemIssuers.length];
            System.arraycopy(customIssuers, 0, combined, 0, customIssuers.length);
            System.arraycopy(systemIssuers, 0, combined, customIssuers.length, systemIssuers.length);
            return combined;
        }
    }
}
