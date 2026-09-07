package com.rbc.fogwall.net;

import org.apache.hc.client5.http.auth.AuthSchemeFactory;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.CredentialsProvider;
import org.apache.hc.client5.http.auth.StandardAuthScheme;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.auth.SPNegoSchemeFactory;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultProxyRoutePlanner;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.config.Lookup;
import org.apache.hc.core5.http.config.RegistryBuilder;

/**
 * Shared HC5 client used by every {@link com.rbc.fogwall.provider.FogwallProvider} that makes REST API calls via the
 * {@code org.apache.hc.client5.http.fluent} API (identity resolution, SSH key listing, etc.) — call sites use
 * {@code Request.get(url).execute(FogwallHttpExecutor.instance())}.
 *
 * <p>Defaults to a plain unconfigured client (no proxy) so providers work unchanged when outbound proxying isn't
 * configured. {@link #configure} is called once at startup by fogwall-server when an outbound proxy is present.
 *
 * <p>For Kerberos auth, the actual ticket acquisition (ticket-cache vs. keytab) is a JVM-wide JAAS concern set up once
 * at startup — see {@code OutboundProxySystemProperties} in fogwall-server. This class only registers the SPNEGO auth
 * scheme and a placeholder credential to trigger it; the GSS context itself is established from whatever Subject that
 * startup wiring configured.
 */
public final class FogwallHttpExecutor {

    private static volatile CloseableHttpClient client =
            HttpClientBuilder.create().build();

    private FogwallHttpExecutor() {}

    /** Returns the shared client. */
    public static CloseableHttpClient instance() {
        return client;
    }

    /**
     * Rebuilds the shared client from a resolved outbound proxy. Call once at startup.
     *
     * <p>{@code SPNegoSchemeFactory} is {@code @Deprecated} in HC5 ("no longer supported, use Basic or Bearer with TLS
     * instead") but still performs a genuine GSS handshake — Apache stopped maintaining it, it didn't stop working.
     * Kept for parity with the JGit and Jetty outbound paths, which both have non-deprecated native Kerberos/SPNEGO
     * support.
     */
    public static void configure(ResolvedOutboundProxy proxy) {
        if (proxy == null || !proxy.isConfigured()) {
            client = HttpClientBuilder.create().build();
            return;
        }

        String proxyHost = proxy.httpsProxyHost() != null ? proxy.httpsProxyHost() : proxy.httpProxyHost();
        int proxyPort = proxy.httpsProxyHost() != null ? proxy.httpsProxyPort() : proxy.httpProxyPort();
        var proxyHttpHost = new HttpHost(proxyHost, proxyPort);

        var clientBuilder = HttpClientBuilder.create().setRoutePlanner(new DefaultProxyRoutePlanner(proxyHttpHost));

        switch (proxy.authType()) {
            case BASIC ->
                clientBuilder.setDefaultCredentialsProvider(
                        basicCredentials(proxyHttpHost, proxy.username(), proxy.password()));
            case KERBEROS -> {
                clientBuilder.setDefaultAuthSchemeRegistry(kerberosSchemeRegistry());
                clientBuilder.setDefaultCredentialsProvider(kerberosPlaceholderCredentials(proxyHttpHost));
            }
            case NONE -> {
                // no credentials — proxy is either open or handles auth upstream itself
            }
        }

        client = clientBuilder.build();
    }

    private static CredentialsProvider basicCredentials(HttpHost proxyHost, String username, String password) {
        var provider = new BasicCredentialsProvider();
        provider.setCredentials(
                new AuthScope(proxyHost), new UsernamePasswordCredentials(username, password.toCharArray()));
        return provider;
    }

    private static Lookup<AuthSchemeFactory> kerberosSchemeRegistry() {
        return RegistryBuilder.<AuthSchemeFactory>create()
                .register(StandardAuthScheme.SPNEGO, SPNegoSchemeFactory.DEFAULT)
                .build();
    }

    /**
     * SPNEGO's actual handshake is driven by the JVM's GSS/JAAS Subject (see class javadoc), not by these credentials —
     * HC5 still requires a non-null {@link org.apache.hc.client5.http.auth.Credentials} registered for the auth scope
     * to select the scheme at all, so this is a placeholder rather than a real secret.
     */
    private static CredentialsProvider kerberosPlaceholderCredentials(HttpHost proxyHost) {
        var provider = new BasicCredentialsProvider();
        provider.setCredentials(new AuthScope(proxyHost), new UsernamePasswordCredentials("", new char[0]));
        return provider;
    }
}
