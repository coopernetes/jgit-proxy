package org.finos.gitproxy.jetty.config;

import lombok.Data;

/** Binds a single entry under {@code providers:} in git-proxy.yml. */
@Data
public class ProviderConfig {

    private boolean enabled = true;

    /** Additional URL prefix for this provider's servlet path. */
    private String servletPath = "";

    /** Upstream base URI. Required for custom providers; omit for built-ins (github, gitlab, bitbucket). */
    private String uri = "";

    /**
     * Provider type. Required for custom-named providers; omit only for the built-in default names ({@code github},
     * {@code gitlab}, {@code bitbucket}, {@code codeberg}, {@code forgejo}). Supported values: {@code github},
     * {@code gitlab}, {@code bitbucket}, {@code codeberg}, {@code forgejo}, {@code gitea}.
     */
    private String type = "";

    /**
     * HTTP status returned to the git client when a {@code /info/refs} discovery request is blocked by URL rules.
     * {@code 403} (default) is unambiguous — clients see a clear denial. Use {@code 404} to obscure whether a
     * repository exists at all (security by obscurity for sensitive environments).
     */
    private int blockedInfoRefsStatus = 403;
}
