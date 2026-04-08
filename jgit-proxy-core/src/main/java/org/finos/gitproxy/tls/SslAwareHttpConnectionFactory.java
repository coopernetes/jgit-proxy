package org.finos.gitproxy.tls;

import java.io.IOException;
import java.net.Proxy;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import javax.net.ssl.TrustManager;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory;

/**
 * A JGit {@link HttpConnectionFactory} that applies custom trust managers to every outbound HTTPS connection, while
 * delegating all other behaviour to {@link JDKHttpConnectionFactory}.
 *
 * <p>JGit's {@code HttpConnection.configure(KeyManager[], TrustManager[], SecureRandom)} is called on each connection
 * with the custom trust managers so that upstream providers signed by an internal CA are trusted without modifying the
 * JVM truststore.
 *
 * <p>Install at startup via {@code HttpTransport.setConnectionFactory(new
 * SslAwareHttpConnectionFactory(upstreamTls.trustManagers()))} before the server starts accepting connections. This
 * affects all JGit HTTP/HTTPS transport in the JVM, which is acceptable because jgit-proxy owns the entire process.
 */
public class SslAwareHttpConnectionFactory implements HttpConnectionFactory {

    private final HttpConnectionFactory delegate = new JDKHttpConnectionFactory();
    private final TrustManager[] trustManagers;

    public SslAwareHttpConnectionFactory(TrustManager[] trustManagers) {
        this.trustManagers = trustManagers;
    }

    @Override
    public HttpConnection create(URL url) throws IOException {
        return configure(delegate.create(url));
    }

    @Override
    public HttpConnection create(URL url, Proxy proxy) throws IOException {
        return configure(delegate.create(url, proxy));
    }

    private HttpConnection configure(HttpConnection conn) throws IOException {
        try {
            conn.configure(null, trustManagers, null);
        } catch (KeyManagementException | NoSuchAlgorithmException e) {
            throw new IOException("Failed to configure SSL trust on JGit HTTP connection", e);
        }
        return conn;
    }
}
