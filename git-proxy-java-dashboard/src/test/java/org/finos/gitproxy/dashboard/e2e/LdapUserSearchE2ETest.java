package org.finos.gitproxy.dashboard.e2e;

import static org.junit.jupiter.api.Assertions.*;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.finos.gitproxy.jetty.config.GitProxyConfig;
import org.junit.jupiter.api.*;

/**
 * End-to-end tests for LDAP authentication using the search-first approach ({@code userSearchFilter} +
 * {@code userSearchBase}).
 *
 * <p>This tests the path added in <a href="https://github.com/coopernetes/git-proxy-java/issues/120">#120</a>: when
 * {@code auth.ldap.user-search-filter} is set, Spring Security uses
 * {@link org.springframework.security.ldap.search.FilterBasedLdapUserSearch} instead of constructing a DN directly from
 * {@code userDnPatterns}. This is necessary for large Active Directory forests where users are spread across many OUs
 * and no single DN pattern applies.
 *
 * <p>The test filter {@code (cn={0})} is analogous to {@code (sAMAccountName={0})} used against real AD servers.
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LdapUserSearchE2ETest {

    static OpenLdapContainer ldap;
    static DashboardFixture dashboard;
    static HttpClient client;
    static String baseUrl;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        ldap = new OpenLdapContainer();
        ldap.start();

        var config = new GitProxyConfig();
        config.getAuth().setProvider("ldap");
        config.getAuth().getLdap().setUrl(ldap.getLdapUrl());
        // Deliberately leave userDnPatterns at its default — userSearchFilter takes precedence.
        config.getAuth().getLdap().setUserSearchFilter(OpenLdapContainer.USER_SEARCH_FILTER);
        config.getAuth().getLdap().setUserSearchBase(OpenLdapContainer.USER_SEARCH_BASE);
        config.getAuth().getLdap().setBindDn(OpenLdapContainer.MANAGER_DN);
        config.getAuth().getLdap().setBindPassword(OpenLdapContainer.ADMIN_PASSWORD);

        dashboard = new DashboardFixture(config);
        baseUrl = dashboard.getBaseUrl();

        var cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        client = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @AfterAll
    static void stopInfrastructure() throws Exception {
        if (dashboard != null) dashboard.close();
        if (ldap != null) ldap.stop();
    }

    @Test
    @Order(1)
    void loginWithValidCredentialsSucceeds() throws Exception {
        String formBody = "username=" + OpenLdapContainer.TEST_USER + "&password=" + OpenLdapContainer.TEST_PASSWORD;

        var resp = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertNotEquals(401, resp.statusCode(), "Search-first login should succeed with valid credentials");
        assertNotEquals(403, resp.statusCode(), "Search-first login should succeed with valid credentials");
    }

    @Test
    @Order(2)
    void authenticatedUserCanAccessMeEndpoint() throws Exception {
        var resp = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/me"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(
                resp.body().contains(OpenLdapContainer.TEST_USER),
                "Response should contain the authenticated username; got: " + resp.body());
    }

    @Test
    @Order(3)
    void loginWithWrongPasswordFails() throws Exception {
        var freshClient = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        String formBody = "username=" + OpenLdapContainer.TEST_USER + "&password=wrongpassword";
        freshClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        var meResp = freshClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/me"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(401, meResp.statusCode(), "Should be 401 after failed login attempt");
    }

    @Test
    @Order(4)
    void groupMembershipGrantsConfiguredRole() throws Exception {
        // Restart with group search enabled on top of the search-first user lookup.
        if (dashboard != null) dashboard.close();

        var config = new GitProxyConfig();
        config.getAuth().setProvider("ldap");
        config.getAuth().getLdap().setUrl(ldap.getLdapUrl());
        config.getAuth().getLdap().setUserSearchFilter(OpenLdapContainer.USER_SEARCH_FILTER);
        config.getAuth().getLdap().setUserSearchBase(OpenLdapContainer.USER_SEARCH_BASE);
        config.getAuth().getLdap().setBindDn(OpenLdapContainer.MANAGER_DN);
        config.getAuth().getLdap().setBindPassword(OpenLdapContainer.ADMIN_PASSWORD);
        config.getAuth().getLdap().setGroupSearchBase(OpenLdapContainer.GROUP_SEARCH_BASE);
        config.getAuth().setRoleMappings(Map.of("ADMIN", List.of(OpenLdapContainer.ADMIN_GROUP)));

        var groupDashboard = new DashboardFixture(config);
        var groupBaseUrl = groupDashboard.getBaseUrl();

        var cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var groupClient = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        String formBody = "username=" + OpenLdapContainer.TEST_USER + "&password=" + OpenLdapContainer.TEST_PASSWORD;
        groupClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(groupBaseUrl + "/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        var meResp = groupClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(groupBaseUrl + "/api/me"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        groupDashboard.close();

        assertEquals(200, meResp.statusCode());
        assertTrue(
                meResp.body().contains("ROLE_ADMIN"),
                "Expected ROLE_ADMIN from group membership when using search-first LDAP; got: " + meResp.body());
    }
}
