package com.rbc.fogwall.jetty;

import com.rbc.fogwall.config.TlsConfig;
import com.rbc.fogwall.tls.SslUtil;
import java.nio.file.Path;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/**
 * Builds Jetty server TLS material from the {@code server.tls:} block, so every listener fogwall opens is secured from
 * one configuration rather than each growing its own copy.
 *
 * <p>The certificate is per-hostname while listeners differ only by port, so the same material covers the main HTTPS
 * listener and every proposals listener. A deployment that fronts each provider with its own hostname and terminates
 * TLS at fogwall rather than at an ingress needs a SAN certificate covering those names.
 */
public final class JettyTls {

    private JettyTls() {}

    /**
     * Server-side TLS from either PEM cert+key or a keystore, whichever {@code server.tls:} configures. Callers must
     * check {@link TlsConfig#isServerTlsConfigured()} first — with neither form set there is nothing to build.
     */
    public static SslContextFactory.Server serverSslContextFactory(TlsConfig tls) throws Exception {
        var sslContextFactory = new SslContextFactory.Server();
        if (tls.getCertificate() != null && tls.getKey() != null) {
            sslContextFactory.setSslContext(
                    SslUtil.buildServerSslContext(Path.of(tls.getCertificate()), Path.of(tls.getKey())));
        } else {
            TlsConfig.KeystoreConfig keystore = tls.getKeystore();
            sslContextFactory.setKeyStorePath(keystore.getPath());
            sslContextFactory.setKeyStorePassword(keystore.getPassword());
            sslContextFactory.setKeyStoreType(keystore.getType());
        }
        return sslContextFactory;
    }
}
