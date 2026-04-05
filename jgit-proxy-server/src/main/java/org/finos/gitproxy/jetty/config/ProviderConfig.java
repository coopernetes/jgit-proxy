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
     * Explicit provider type. Use this when the provider name does not contain the type keyword (e.g. a GitHub
     * Enterprise Server named {@code my-internal-github} should set {@code type: github}). Supported values:
     * {@code github}, {@code gitlab}, {@code bitbucket}, {@code codeberg}, {@code forgejo}.
     *
     * <p>When omitted, the type is inferred from the provider name (e.g. a name containing "github" → GitHub).
     */
    private String type = "";
}
