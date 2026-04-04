package org.finos.gitproxy.jetty.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * Root configuration POJO. Bound from {@code git-proxy.yml} (and optional {@code git-proxy-local.yml} overrides) via
 * Gestalt.
 *
 * <p>Top-level structure:
 *
 * <pre>
 * server:       → {@link ServerConfig}
 * database:     → {@link DatabaseConfig}
 * providers:    → Map&lt;name, {@link ProviderConfig}&gt;
 * commit:       → {@link CommitSettings}
 * filters:      → {@link FiltersConfig}
 * </pre>
 */
@Data
public class GitProxyConfig {

    private ServerConfig server = new ServerConfig();
    private DatabaseConfig database = new DatabaseConfig();
    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();
    private CommitSettings commit = new CommitSettings();
    private FiltersConfig filters = new FiltersConfig();

    /**
     * Optional list of proxy users. Each entry defines a username, BCrypt password hash, email addresses, and SCM
     * identities. When non-empty, these users are the authoritative source for authentication and push authorization.
     * When empty, all pushes are permitted (legacy / open mode).
     */
    private List<UserConfig> users = new ArrayList<>();

    /**
     * Optional service URL for dashboard links embedded in block messages. Defaults to {@code http://localhost:<port>}
     * when not set.
     */
    private String serviceUrl;
}
