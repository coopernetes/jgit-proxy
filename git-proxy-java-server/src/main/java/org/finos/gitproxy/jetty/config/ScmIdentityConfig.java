package org.finos.gitproxy.jetty.config;

import lombok.Data;

/** Binds a single entry in the {@code scm-identities:} list under a user in git-proxy.yml. */
@Data
public class ScmIdentityConfig {
    /**
     * Provider ID in {@code type/host} format, e.g. {@code github/github.com} or {@code gitlab/gitlab.com}. Must match
     * the provider ID derived from the configured provider (type + URI host).
     */
    private String provider = "";

    /** Username on that provider. */
    private String username = "";
}
