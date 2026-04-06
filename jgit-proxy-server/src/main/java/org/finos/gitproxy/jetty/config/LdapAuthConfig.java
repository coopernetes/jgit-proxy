package org.finos.gitproxy.jetty.config;

import lombok.Data;

/** Binds the {@code auth.ldap} block in git-proxy.yml. */
@Data
public class LdapAuthConfig {

    /**
     * LDAP server URL including base DN, e.g. {@code ldap://localhost:389/dc=example,dc=com}. The base DN is appended
     * to all relative DN patterns.
     */
    private String url = "";

    /**
     * User DN pattern relative to the base DN; {@code {0}} is substituted with the login username. Example:
     * {@code cn={0},ou=users} resolves to {@code cn=alice,ou=users,dc=example,dc=com}.
     */
    private String userDnPatterns = "uid={0}";

    /**
     * Optional manager DN used to bind when performing group searches or attribute lookups. Leave blank when anonymous
     * search is sufficient (e.g. bind-only auth with no attribute population).
     */
    private String managerDn = "";

    /** Password for the manager DN. Ignored when {@code managerDn} is blank. */
    private String managerPassword = "";

    /**
     * Base DN for group search, relative to the base DN in {@code url}. When set, group membership is used to derive
     * roles via {@code auth.role-mappings}. Example: {@code ou=groups}.
     */
    private String groupSearchBase = "";

    /**
     * LDAP filter for group membership. {@code {0}} is substituted with the user's full DN. Defaults to the standard
     * member-attribute filter.
     */
    private String groupSearchFilter = "(member={0})";
}
