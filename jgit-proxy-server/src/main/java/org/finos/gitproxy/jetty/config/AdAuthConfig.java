package org.finos.gitproxy.jetty.config;

import lombok.Data;

/** Binds the {@code auth.ad} block in git-proxy.yml. */
@Data
public class AdAuthConfig {

    /**
     * Active Directory domain name, e.g. {@code corp.example.com}. Used to construct the user principal name
     * ({@code user@corp.example.com}) for bind authentication. Required when {@code auth.provider=ad}.
     */
    private String domain = "";

    /**
     * LDAP URL for the domain controller, e.g. {@code ldap://dc.corp.example.com:389}. When blank, Spring Security will
     * attempt to resolve the domain controller via DNS SRV records on {@code domain}.
     */
    private String url = "";

    /**
     * Base DN to search for group membership, e.g. {@code DC=corp,DC=example,DC=com}. When set, group membership is
     * used to derive roles via {@code auth.role-mappings}.
     */
    private String groupSearchBase = "";

    /**
     * LDAP filter for group membership. {@code {0}} is substituted with the user's full DN. Defaults to the standard
     * member-attribute filter.
     */
    private String groupSearchFilter = "(member={0})";
}
