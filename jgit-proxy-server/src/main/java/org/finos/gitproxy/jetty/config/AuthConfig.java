package org.finos.gitproxy.jetty.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * Binds the {@code auth:} block in git-proxy.yml. Selects the active authentication provider and holds its settings.
 *
 * <p>Supported providers:
 *
 * <ul>
 *   <li>{@code local} (default) — usernames and BCrypt password hashes configured directly in {@code users:}
 *   <li>{@code ldap} — bind authentication against a generic LDAP directory
 *   <li>{@code ad} — bind authentication against Active Directory using UPN ({@code user@domain})
 *   <li>{@code oidc} — OpenID Connect authorization code flow (e.g. Keycloak, Dex, Entra ID)
 * </ul>
 *
 * <p>Example YAML:
 *
 * <pre>
 * auth:
 *   provider: ldap
 *   ldap:
 *     url: ldap://localhost:389/dc=example,dc=com
 *     user-dn-patterns: cn={0},ou=users
 * </pre>
 */
@Data
public class AuthConfig {

    /** Active authentication provider. Accepted values: {@code local}, {@code ldap}, {@code ad}, {@code oidc}. */
    private String provider = "local";

    /**
     * Maps jgit-proxy role names to lists of IdP group names. When a user belongs to any listed group, the
     * corresponding role is granted. Applies to OIDC (via {@code groups-claim}) and LDAP (via group search).
     *
     * <p>Example:
     *
     * <pre>
     * auth:
     *   role-mappings:
     *     APPROVER:
     *       - "git-approvers"
     *       - "security-team"
     *     ADMIN:
     *       - "git-admins"
     * </pre>
     */
    private Map<String, List<String>> roleMappings = new HashMap<>();

    /**
     * OIDC claim name that contains the user's group memberships. Defaults to {@code groups}, which is standard for
     * Keycloak, Okta, and most Entra ID configurations. Override if your IdP uses a different claim (e.g.
     * {@code roles}, {@code memberOf}).
     */
    private String groupsClaim = "groups";

    private LdapAuthConfig ldap = new LdapAuthConfig();
    private AdAuthConfig ad = new AdAuthConfig();
    private OidcAuthConfig oidc = new OidcAuthConfig();
}
