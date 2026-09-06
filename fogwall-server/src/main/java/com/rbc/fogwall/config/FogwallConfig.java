package com.rbc.fogwall.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * Root configuration POJO. Bound from {@code fogwall.yml} (and optional {@code fogwall-local.yml} overrides) via
 * Gestalt.
 *
 * <p>Top-level structure:
 *
 * <pre>
 * server:          → {@link ServerConfig}  (includes service-url for dashboard links)
 * database:        → {@link DatabaseConfig}
 * providers:       → Map&lt;name, {@link ProviderConfig}&gt;
 * commit:          → {@link CommitSettings}   (per-commit: identity, author email, message)
 * diff-scan:       → {@link DiffScanSettings} (push-level: blocked patterns in diff content)
 * secret-scan:     → {@link SecretScanSettings} (push-level: gitleaks integration)
 * binary-blob:     → {@link BinaryBlobSettings} (push-level: blob size / extension / MIME denylist)
 * content-patterns: → {@link ContentPatternSettings} (push-level: built-in PII/identifier bundle scanning, WARN-only)
 * attestations:    → List&lt;{@link AttestationQuestion}&gt; (global reviewer prompts)
 * rules:           → {@link RulesConfig}
 * scm-oauth:       → {@link ScmOAuthSettings} (OAuth account linking + verified-identity enforcement mode, #40)
 * proposals:       → {@link ProposalsSettings} (proposing changes through fogwall)
 * </pre>
 */
@Data
public class FogwallConfig {

    private ServerConfig server = new ServerConfig();
    private DatabaseConfig database = new DatabaseConfig();
    private CacheConfig cache = new CacheConfig();
    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();
    private CommitSettings commit = new CommitSettings();
    private DiffScanSettings diffScan = new DiffScanSettings();
    private SecretScanSettings secretScan = new SecretScanSettings();
    private BinaryBlobSettings binaryBlob = new BinaryBlobSettings();
    private ContentPatternSettings contentPatterns = new ContentPatternSettings();
    private RulesConfig rules = new RulesConfig();
    private ScmOAuthSettings scmOauth = new ScmOAuthSettings();
    private ProposalsSettings proposals = new ProposalsSettings();

    /**
     * Global attestation questions shown to reviewers in the dashboard approval form. Applies to all providers —
     * per-provider variants are a future enhancement. Required questions block approval submission until answered.
     * Hot-reloadable via {@code POST /api/config/reload?section=attestations}.
     */
    private List<AttestationQuestion> attestations = new ArrayList<>();

    /**
     * Authentication provider configuration. Selects the active provider ({@code static}, {@code ldap}, {@code oidc})
     * and holds its settings. Defaults to {@code static} (password hashes in {@code users:} list).
     */
    private AuthConfig auth = new AuthConfig();

    /**
     * Optional list of proxy users. Each entry defines a username, BCrypt password hash, email addresses, and SCM
     * identities. When non-empty, these users are the authoritative source for authentication and push authorization.
     * When empty, all pushes are permitted (legacy / open mode).
     */
    private List<UserConfig> users = new ArrayList<>();

    /**
     * CONFIG-sourced repo permissions seeded on startup. These supplement (and on restart replace) any permissions with
     * source=CONFIG that were previously stored. DB-sourced permissions (created via the REST API) are never touched by
     * this list.
     */
    private List<PermissionConfig> permissions = new ArrayList<>();

    /**
     * CONFIG-sourced permission groups seeded on startup. Each group has a name, optional description, a list of member
     * usernames, and a list of repository permission rules that all members inherit.
     */
    private List<GroupConfig> groups = new ArrayList<>();

    /**
     * Live config reload settings. Controls hot-reloading of commit rules and auth config without restarting the
     * server. Provider, server, and database changes always require a restart.
     */
    private ReloadConfig reload = new ReloadConfig();

    /**
     * Optional dashboard UI feature flags. Controls which experimental or opt-in features are visible to reviewers.
     * Defaults to all features disabled.
     */
    private DashboardConfig dashboard = new DashboardConfig();
}
