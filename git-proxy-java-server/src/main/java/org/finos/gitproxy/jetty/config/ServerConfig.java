package org.finos.gitproxy.jetty.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** Binds the {@code server:} block in git-proxy.yml. */
@Data
public class ServerConfig {

    private int port = 8080;

    /**
     * Origins permitted for CORS requests to the dashboard REST API. Used when the frontend is served from a different
     * hostname or port than the backend (e.g. behind a load balancer with a separate public hostname).
     *
     * <p>Empty list (default) restricts to same-origin only. Set {@code ["*"]} to allow all origins (not recommended
     * for production). Configurable at runtime via {@code GITPROXY_SERVER_ALLOWEDORIGINS} env var (comma-separated) or
     * the {@code server.allowed-origins} YAML key.
     *
     * <p>Example: {@code ["https://dashboard.example.com", "https://proxy.example.com"]}
     */
    private List<String> allowedOrigins = new ArrayList<>();

    /**
     * Approval mode for store-and-forward pushes. Values: {@code auto} (default), {@code ui}, {@code servicenow}.
     *
     * <p>Note: {@code GitProxyWithDashboardApplication} always uses {@code ui} regardless of this setting.
     */
    private String approvalMode = "auto";

    /**
     * Sideband keepalive interval in seconds. Sends periodic progress packets during long operations (secret scanning,
     * approval polling) to prevent idle-timeout disconnects. Set to 0 to disable.
     */
    private int heartbeatIntervalSeconds = 10;

    /**
     * When {@code true}, the validation pipeline stops after the first failed check rather than collecting all issues.
     * The developer sees only the first problem per push. Defaults to {@code false} (collect all issues).
     */
    private boolean failFast = false;

    /**
     * Connection timeout in seconds for store-and-forward upstream pushes
     * ({@link org.eclipse.jgit.transport.Transport#setTimeout}). Set to 0 to use JGit's default (no timeout).
     * Enterprises with slow or inspecting middleboxes should set this to a generous value (e.g. 120) rather than
     * leaving it unbounded.
     */
    private int upstreamConnectTimeoutSeconds = 0;

    /**
     * Total request timeout in seconds for transparent-proxy forwarding. Applied as the Jetty {@code HttpClient}
     * connect timeout. Set to 0 to use Jetty's default (no timeout).
     */
    private int proxyConnectTimeoutSeconds = 0;

    /**
     * When {@code false} (default), any authenticated user may review any push they did not push themselves.
     * {@code REVIEW} permission entries are still respected for notification and filtering purposes, but are not
     * required to approve or reject.
     *
     * <p>When {@code true}, a user must have an explicit {@code REVIEW} (or {@code PUSH_AND_REVIEW}) permission entry
     * for the repository to approve or reject pushes to it. Set this for deployments that require restricted approvers
     * (e.g. formal sign-off with compliance liability).
     */
    private boolean requireReviewPermission = false;

    /** TLS configuration for the server listener and upstream trust. Omit entirely to use plain HTTP. */
    private TlsConfig tls = new TlsConfig();

    /**
     * HTTP session persistence backend. Values:
     *
     * <ul>
     *   <li>{@code none} (default) — in-memory sessions; sessions are lost on restart and not shared across instances
     *   <li>{@code jdbc} — persisted to the configured JDBC database ({@code h2-file} or {@code postgres}); zero new
     *       infrastructure required, uses the same DataSource as the push store
     *   <li>{@code redis} — persisted to a Redis or Valkey instance; configure connection via {@code server.redis.*}
     * </ul>
     *
     * <p>Use {@code jdbc} or {@code redis} when running multiple instances sharing a database so that authenticated
     * sessions survive pod restarts and are visible across all instances.
     */
    private String sessionStore = "none";

    /** Redis connection settings — used when {@code server.session-store: redis}. */
    private RedisConfig redis = new RedisConfig();

    /** Binds the {@code server.redis:} block. */
    @Data
    public static class RedisConfig {
        private String host = "localhost";
        private int port = 6379;
        private String password = "";
        private boolean ssl = false;
    }
}
