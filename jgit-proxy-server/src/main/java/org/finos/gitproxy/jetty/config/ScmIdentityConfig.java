package org.finos.gitproxy.jetty.config;

import lombok.Data;

/** Binds a single entry in the {@code scm-identities:} list under a user in git-proxy.yml. */
@Data
public class ScmIdentityConfig {
    /** Provider name, e.g. {@code github} or {@code gitlab}. */
    private String provider = "";

    /** Username on that provider. */
    private String username = "";
}
