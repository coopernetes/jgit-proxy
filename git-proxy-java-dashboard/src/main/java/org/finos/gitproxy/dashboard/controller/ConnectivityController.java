package org.finos.gitproxy.dashboard.controller;

import jakarta.annotation.Resource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.config.ProviderConfigurationSource;
import org.finos.gitproxy.jetty.config.GitProxyConfig;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.tls.SslUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin endpoint that tests outbound connectivity to each configured upstream provider. Useful for diagnosing
 * network-level failures in firewalled environments.
 *
 * <p>Runs three checks per provider in sequence, stopping at the first failure:
 *
 * <ol>
 *   <li><b>TCP</b> — open a socket to {@code host:port} (5 s timeout). Classifies the error as REFUSED, TIMEOUT, or
 *       RESET so firewall DROP vs REJECT is immediately distinguishable.
 *   <li><b>TLS</b> — complete the TLS handshake (HTTPS providers only). Reports negotiated protocol and cipher, or the
 *       specific exception class for certificate / SNI failures.
 *   <li><b>HTTP</b> — send {@code GET /} and record the status code and response time.
 *   <li><b>Git probe</b> (targeted check only) — send {@code GET /info/refs?service=git-upload-pack} and {@code GET
 *       /info/refs?service=git-receive-pack} with {@code User-Agent: git/2.x.x} to a specific repo URL. Distinguishes
 *       appliances that pass generic HTTP but block git-specific URL patterns, query strings, or user-agent strings.
 * </ol>
 *
 * <p>Every step is logged at INFO level. Targeted checks additionally return a structured {@code steps} log in the API
 * response so that the output can be shared with network teams without requiring access to server logs.
 *
 * <p>Requires {@code ROLE_ADMIN}.
 */
@Slf4j
@RestController
public class ConnectivityController {

    private static final int TIMEOUT_MS = 5000;

    @Resource(name = "providers")
    private ProviderConfigurationSource providers;

    @Autowired
    private GitProxyConfig gitProxyConfig;

    /**
     * Runs connectivity checks for all providers (no git probe), or a targeted check for a single provider with an
     * optional git probe step.
     *
     * @param providerName optional — name of the provider to target; if absent all providers are checked
     * @param repoPath optional — repo path (e.g. {@code /owner/repo.git}) appended to the provider base URI for the git
     *     probe step; requires {@code provider}
     */
    @GetMapping("/api/admin/connectivity")
    public Map<String, Object> check(
            @RequestParam(name = "provider", required = false) String providerName,
            @RequestParam(required = false) String repoPath) {
        SSLContext sslContext = buildSslContext();
        Instant checkedAt = Instant.now();

        log.info("=== Connectivity check started at {} ===", checkedAt);

        Map<String, Object> providerResults = new LinkedHashMap<>();

        if (providerName != null) {
            // Targeted: single provider, structured step log, optional git probe
            GitProxyProvider provider = providers.getProviders().stream()
                    .filter(p -> p.getName().equals(providerName))
                    .findFirst()
                    .orElseThrow(() ->
                            new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown provider: " + providerName));
            log.info("--- Provider: {} ({}) [targeted] ---", provider.getName(), provider.getUri());
            List<Map<String, Object>> steps = new ArrayList<>();
            Map<String, Object> result = checkProvider(provider, sslContext, steps);
            if (repoPath != null) {
                String repoUrl = provider.getUri().toString().replaceAll("/+$", "")
                        + (repoPath.startsWith("/") ? repoPath : "/" + repoPath);
                Map<String, Object> gitProbe = new LinkedHashMap<>();
                gitProbe.put("uploadPack", probe(repoUrl, "git-upload-pack", sslContext, steps));
                gitProbe.put("receivePack", probe(repoUrl, "git-receive-pack", sslContext, steps));
                result.put("gitProbe", gitProbe);
            }
            result.put("steps", steps);
            providerResults.put(provider.getName(), result);
        } else {
            // Baseline: all providers, no git probe, no step log
            for (GitProxyProvider provider : providers.getProviders()) {
                log.info("--- Provider: {} ({}) ---", provider.getName(), provider.getUri());
                providerResults.put(provider.getName(), checkProvider(provider, sslContext, null));
            }
        }

        log.info("=== Connectivity check complete ===");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkedAt", checkedAt.toString());
        result.put("providers", providerResults);
        return result;
    }

    private Map<String, Object> checkProvider(
            GitProxyProvider provider, SSLContext sslContext, List<Map<String, Object>> steps) {
        URI uri = provider.getUri();
        boolean isHttps = "https".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort() > 0 ? uri.getPort() : (isHttps ? 443 : 80);
        String host = uri.getHost();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uri", uri.toString());

        // ── TCP connect ───────────────────────────────────────────────────────
        long tcpStart = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            long tcpMs = System.currentTimeMillis() - tcpStart;
            log.info("[{}] TCP {}:{} → OK ({} ms)", provider.getName(), host, port, tcpMs);
            addStep(steps, "TCP", "ok", tcpMs, host + ":" + port + " → OK");
            Map<String, Object> tcp = new LinkedHashMap<>();
            tcp.put("status", "ok");
            tcp.put("host", host);
            tcp.put("port", port);
            tcp.put("durationMs", tcpMs);
            out.put("tcp", tcp);
        } catch (Exception e) {
            long tcpMs = System.currentTimeMillis() - tcpStart;
            String errorCode = classifyTcpError(e);
            log.info(
                    "[{}] TCP {}:{} → {} ({} ms) — {}: {}",
                    provider.getName(),
                    host,
                    port,
                    errorCode,
                    tcpMs,
                    e.getClass().getSimpleName(),
                    e.getMessage());
            addStep(
                    steps,
                    "TCP",
                    "error",
                    tcpMs,
                    host + ":" + port + " → " + errorCode + " — " + e.getClass().getSimpleName() + ": "
                            + e.getMessage());
            Map<String, Object> tcp = new LinkedHashMap<>();
            tcp.put("status", "error");
            tcp.put("error", errorCode);
            tcp.put("detail", e.getClass().getSimpleName() + ": " + e.getMessage());
            tcp.put("host", host);
            tcp.put("port", port);
            tcp.put("durationMs", tcpMs);
            out.put("tcp", tcp);
            out.put("tls", null);
            out.put("http", null);
            return out;
        }

        // ── TLS handshake (HTTPS only) ────────────────────────────────────────
        if (isHttps) {
            long tlsStart = System.currentTimeMillis();
            SSLSocketFactory factory = sslContext.getSocketFactory();
            try (SSLSocket ssl = (SSLSocket) factory.createSocket(host, port)) {
                ssl.setSoTimeout(TIMEOUT_MS);
                ssl.startHandshake();
                long tlsMs = System.currentTimeMillis() - tlsStart;
                var session = ssl.getSession();
                String protocol = session.getProtocol();
                String cipher = session.getCipherSuite();
                String peerCn = peerCommonName(ssl);
                log.info(
                        "[{}] TLS {}:{} → OK ({} ms) — {} / {} / peer={}",
                        provider.getName(),
                        host,
                        port,
                        tlsMs,
                        protocol,
                        cipher,
                        peerCn);
                addStep(steps, "TLS", "ok", tlsMs, protocol + " / " + cipher + " / CN=" + peerCn);
                Map<String, Object> tls = new LinkedHashMap<>();
                tls.put("status", "ok");
                tls.put("protocol", protocol);
                tls.put("cipher", cipher);
                tls.put("peerCn", peerCn);
                tls.put("durationMs", tlsMs);
                out.put("tls", tls);
            } catch (SSLHandshakeException e) {
                long tlsMs = System.currentTimeMillis() - tlsStart;
                String errorCode = classifyTlsError(e);
                log.info(
                        "[{}] TLS {}:{} → {} ({} ms) — {}: {}",
                        provider.getName(),
                        host,
                        port,
                        errorCode,
                        tlsMs,
                        e.getClass().getSimpleName(),
                        e.getMessage());
                addStep(
                        steps,
                        "TLS",
                        "error",
                        tlsMs,
                        errorCode + " — " + e.getClass().getSimpleName() + ": " + e.getMessage());
                Map<String, Object> tls = new LinkedHashMap<>();
                tls.put("status", "error");
                tls.put("error", errorCode);
                tls.put("detail", e.getClass().getSimpleName() + ": " + e.getMessage());
                tls.put("durationMs", tlsMs);
                out.put("tls", tls);
                out.put("http", null);
                return out;
            } catch (Exception e) {
                long tlsMs = System.currentTimeMillis() - tlsStart;
                log.info(
                        "[{}] TLS {}:{} → ERROR ({} ms) — {}: {}",
                        provider.getName(),
                        host,
                        port,
                        tlsMs,
                        e.getClass().getSimpleName(),
                        e.getMessage());
                addStep(
                        steps,
                        "TLS",
                        "error",
                        tlsMs,
                        "ERROR — " + e.getClass().getSimpleName() + ": " + e.getMessage());
                Map<String, Object> tls = new LinkedHashMap<>();
                tls.put("status", "error");
                tls.put("error", "ERROR");
                tls.put("detail", e.getClass().getSimpleName() + ": " + e.getMessage());
                tls.put("durationMs", tlsMs);
                out.put("tls", tls);
                out.put("http", null);
                return out;
            }
        } else {
            addStep(steps, "TLS", "skipped", null, "not applicable (HTTP provider)");
            out.put("tls", null);
        }

        // ── HTTP probe ────────────────────────────────────────────────────────
        long httpStart = System.currentTimeMillis();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .connectTimeout(Duration.ofMillis(TIMEOUT_MS))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(Duration.ofMillis(TIMEOUT_MS))
                    .build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            long httpMs = System.currentTimeMillis() - httpStart;
            int status = resp.statusCode();
            String location = resp.headers().firstValue("location").orElse(null);
            String stepDetail = "GET " + uri + " → " + status;
            if (location != null) stepDetail += "  →  " + location;
            if (location != null) {
                log.info(
                        "[{}] HTTP GET {} → {} ({} ms) — Location: {}",
                        provider.getName(),
                        uri,
                        status,
                        httpMs,
                        location);
            } else {
                log.info("[{}] HTTP GET {} → {} ({} ms)", provider.getName(), uri, status, httpMs);
            }
            addStep(steps, "HTTP", "ok", httpMs, stepDetail);
            Map<String, Object> http = new LinkedHashMap<>();
            http.put("status", status);
            if (location != null) http.put("location", location);
            http.put("durationMs", httpMs);
            out.put("http", http);
        } catch (Exception e) {
            long httpMs = System.currentTimeMillis() - httpStart;
            log.info(
                    "[{}] HTTP GET {} → ERROR ({} ms) — {}: {}",
                    provider.getName(),
                    uri,
                    httpMs,
                    e.getClass().getSimpleName(),
                    e.getMessage());
            addStep(
                    steps,
                    "HTTP",
                    "error",
                    httpMs,
                    "GET " + uri + " → ERROR — " + e.getClass().getSimpleName() + ": " + e.getMessage());
            Map<String, Object> http = new LinkedHashMap<>();
            http.put("status", "error");
            http.put("detail", e.getClass().getSimpleName() + ": " + e.getMessage());
            http.put("durationMs", httpMs);
            out.put("http", http);
        }

        return out;
    }

    /**
     * Sends {@code GET /info/refs?service=<service>} with a real git user-agent to {@code repoUrl}. Any HTTP response
     * (200, 401, 403, 404 …) means the request reached the upstream — the git URL patterns are not being filtered. A
     * network-level error (TIMEOUT, RESET) after TCP/TLS passed indicates git-specific DLP blocking.
     */
    private Map<String, Object> probe(
            String repoUrl, String service, SSLContext sslContext, List<Map<String, Object>> steps) {
        String probeUrl = repoUrl.replaceAll("/+$", "") + "/info/refs?service=" + service;
        String stepName = "git-upload-pack".equals(service) ? "Git fetch" : "Git push";
        log.info("[git-probe:{}] GET {}", service, probeUrl);
        long start = System.currentTimeMillis();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .connectTimeout(Duration.ofMillis(TIMEOUT_MS))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(probeUrl))
                    .GET()
                    .header("User-Agent", "git/2.x.x")
                    .timeout(Duration.ofMillis(TIMEOUT_MS))
                    .build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            long ms = System.currentTimeMillis() - start;
            int status = resp.statusCode();
            Optional<String> contentType = resp.headers().firstValue("content-type");
            log.info(
                    "[git-probe:{}] GET {} → {} ({} ms) content-type={}",
                    service,
                    probeUrl,
                    status,
                    ms,
                    contentType.orElse("none"));
            String stepDetail = "GET " + probeUrl + " → " + status;
            if (contentType.isPresent()) stepDetail += "  content-type: " + contentType.get();
            addStep(steps, stepName, "ok", ms, stepDetail);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("probeUrl", probeUrl);
            result.put("httpStatus", status);
            contentType.ifPresent(ct -> result.put("contentType", ct));
            result.put("durationMs", ms);
            return result;
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;
            String errorCode = classifyNetworkError(e);
            log.info(
                    "[git-probe:{}] GET {} → {} ({} ms) — {}: {}",
                    service,
                    probeUrl,
                    errorCode,
                    ms,
                    e.getClass().getSimpleName(),
                    e.getMessage());
            addStep(
                    steps,
                    stepName,
                    "error",
                    ms,
                    "GET " + probeUrl + " → " + errorCode + " — " + e.getClass().getSimpleName() + ": "
                            + e.getMessage());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "error");
            result.put("probeUrl", probeUrl);
            result.put("error", errorCode);
            result.put("detail", e.getClass().getSimpleName() + ": " + e.getMessage());
            result.put("durationMs", ms);
            return result;
        }
    }

    /**
     * Appends a step entry to {@code steps} if non-null. Safe to call with a null list (baseline checks don't collect
     * steps).
     */
    private static void addStep(
            List<Map<String, Object>> steps, String step, String status, Long durationMs, String detail) {
        if (steps == null) return;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", Instant.now().toString());
        entry.put("step", step);
        entry.put("status", status);
        if (durationMs != null) entry.put("durationMs", durationMs);
        entry.put("detail", detail);
        steps.add(entry);
    }

    private static String classifyTcpError(Exception e) {
        return ConnectivityErrorClassifier.classifyTcpError(e);
    }

    private static String classifyNetworkError(Exception e) {
        return ConnectivityErrorClassifier.classifyNetworkError(e);
    }

    private static String classifyTlsError(SSLHandshakeException e) {
        return ConnectivityErrorClassifier.classifyTlsError(e);
    }

    /** Extracts the CN from the peer certificate, or returns the host name as fallback. */
    private static String peerCommonName(SSLSocket ssl) {
        try {
            var certs = ssl.getSession().getPeerCertificates();
            if (certs != null && certs.length > 0 && certs[0] instanceof X509Certificate x509) {
                String dn = x509.getSubjectX500Principal().getName();
                for (String part : dn.split(",")) {
                    part = part.strip();
                    if (part.startsWith("CN=")) return part.substring(3);
                }
            }
        } catch (SSLPeerUnverifiedException ignored) {
            // anonymous cipher — no peer cert
        }
        return ssl.getInetAddress().getHostName();
    }

    private SSLContext buildSslContext() {
        var tlsCfg = gitProxyConfig.getServer().getTls();
        if (tlsCfg != null && tlsCfg.isUpstreamTrustConfigured()) {
            try {
                return SslUtil.buildUpstreamTls(Path.of(tlsCfg.getTrustCaBundle()))
                        .sslContext();
            } catch (Exception e) {
                log.warn("Failed to build SSL context from trust-ca-bundle; using JVM defaults: {}", e.getMessage());
            }
        }
        try {
            return SSLContext.getDefault();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to get default SSLContext", e);
        }
    }
}
