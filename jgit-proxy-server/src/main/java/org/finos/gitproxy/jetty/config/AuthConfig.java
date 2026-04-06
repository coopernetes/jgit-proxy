package org.finos.gitproxy.jetty.config;

import lombok.Data;

/**
 * Binds the {@code auth:} block in git-proxy.yml. Selects the active authentication provider and holds its settings.
 *
 * <p>Supported providers:
 *
 * <ul>
 *   <li>{@code local} (default) — usernames and BCrypt password hashes configured directly in {@code users:}
 *   <li>{@code ldap} — bind authentication against an LDAP/AD directory
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

    /** Active authentication provider. Accepted values: {@code local}, {@code ldap}, {@code oidc}. */
    private String provider = "local";

    private LdapAuthConfig ldap = new LdapAuthConfig();
    private OidcAuthConfig oidc = new OidcAuthConfig();
}
