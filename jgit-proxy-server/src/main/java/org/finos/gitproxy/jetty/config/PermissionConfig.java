package org.finos.gitproxy.jetty.config;

import lombok.Data;

/**
 * Binds a single entry in the {@code permissions:} list in git-proxy.yml.
 *
 * <p>Example:
 *
 * <pre>
 * permissions:
 *   - username: alice
 *     provider: github
 *     path: /owner/repo
 *     operations: PUSH        # PUSH | APPROVE | ALL (default: ALL)
 *     path-type: LITERAL      # LITERAL | GLOB (default: LITERAL)
 * </pre>
 */
@Data
public class PermissionConfig {

    /** Proxy username that this grant applies to. */
    private String username = "";

    /** Provider name (e.g. {@code github}, {@code gitea}). */
    private String provider = "";

    /**
     * Repository path pattern. For {@code LITERAL}: exact match (e.g. {@code /owner/repo}). For {@code GLOB}: shell
     * glob (e.g. {@code /owner/*}).
     */
    private String path = "";

    /** How {@code path} is matched. {@code LITERAL} (default) or {@code GLOB}. */
    private String pathType = "LITERAL";

    /** Which operations are granted. {@code PUSH}, {@code APPROVE}, or {@code ALL} (default). */
    private String operations = "ALL";
}
