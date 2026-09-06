package com.rbc.fogwall.config;

import lombok.Data;

/** Binds a single entry under {@code providers:} in fogwall.yml. */
@Data
public class ProviderConfig {

    private boolean enabled = true;

    /**
     * Custom URL suffix (appended after {@link com.rbc.fogwall.jetty.FogwallServletRegistrar#PROXY_PATH_PREFIX} and
     * {@link com.rbc.fogwall.jetty.FogwallServletRegistrar#PUSH_PATH_PREFIX}) for this provider's servlet path. Allows
     * operators to set a specific path to listen for git requests. By default, this is computed from the provider's
     * hostname & optional port (80/443 are implied for http/https respectively)
     */
    private String pathSuffix = "";

    /** Upstream base URI. Required for custom providers; omit for built-ins (github, gitlab, bitbucket). */
    private String uri = "";

    /**
     * HTTP base URI for provider REST API calls (identity resolution, SSH key lookup). Only required when the HTTP API
     * port differs from what can be derived from {@link #uri} — specifically, when {@link #uri} is an SSH URI and the
     * HTTP API runs on a non-standard port. For public deployments ({@code ssh://git@host}), the API URL is derived
     * automatically by replacing the scheme with {@code https://} and using the hostname. Set this only for local or
     * self-hosted setups where the HTTP and SSH ports are both non-standard (e.g. local Gitea with SSH on 3022 and HTTP
     * on 3000).
     *
     * <pre>
     * gitea-local:
     *   type: forgejo
     *   uri: ssh://git@localhost:3022
     *   api-uri: http://localhost:3000
     * </pre>
     */
    private String apiUri = "";

    /**
     * Personal access token used when the provider's SSH key listing API requires authentication. Forgejo and GitLab
     * instances configured with {@code REQUIRE_SIGNIN_VIEW=true} will reject unauthenticated calls to {@code GET
     * /api/v1/users/{login}/keys}. Set this to a read-only PAT for a service account with visibility of all users whose
     * keys need to be resolved. GitHub's equivalent endpoint is public and does not need a token.
     */
    private String apiToken = "";

    /**
     * Provider type. Required for providers configured with custom names or providers with no canonical URI (Forgejo,
     * generic); this field can only be unset for the built-in default names when a canonical hostname is known (i.e.
     * {@code name=github}, implies {@code type=github,uri=https://github.com}). Supported values: {@code github},
     * {@code gitlab}, {@code bitbucket}, {@code codeberg}, {@code gitea}.
     */
    private String type = "";

    /**
     * HTTP status returned to the git client when a {@code /info/refs} discovery request is blocked by URL rules.
     * {@code 403} (default) is unambiguous — clients see a clear denial. Use {@code 404} to obscure whether a
     * repository exists at all (security by obscurity for sensitive environments).
     */
    private int blockedInfoRefsStatus = 403;

    /**
     * Per-provider override for whether server mode serves clone/fetch from the local mirror (fogwall#478).
     * {@code null} (default) inherits the global {@link ServerConfig#isServeFetch()} ({@code server.serve-fetch}); set
     * {@code true} or {@code false} to override it for this provider only. Applies to both transports of server mode
     * (HTTP and SSH).
     */
    private Boolean serveFetch = null;

    /** OAuth account-linking settings for this provider instance (#40). See docs/CONFIGURATION.md#scm-oauth. */
    private OAuthProviderSettings oauth = new OAuthProviderSettings();

    /** Proposal settings for this provider instance. See docs/CONFIGURATION.md#proposals. */
    private ProposalsProviderSettings proposals = new ProposalsProviderSettings();

    /**
     * SSH transport settings for this provider instance (#531). Lets a single entry serve both HTTP and SSH access to
     * the same upstream. See docs/CONFIGURATION.md#ssh.
     */
    private SshProviderConfig ssh = new SshProviderConfig();
}
