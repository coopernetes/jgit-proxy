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
 * End-to-end tests for LDAP group-to-role mapping.
 *
 * <p>Starts a Bitnami OpenLDAP container with a test user that is a member of an LDAP group, then configures
 * {@code auth.role-mappings} to map that group to {@code ROLE_ADMIN}. Verifies that after login the {@code /api/me}
 * response includes the expected authority.
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LdapRoleMappingE2ETest {

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
        config.getAuth().getLdap().setUserDnPatterns(OpenLdapContainer.USER_DN_PATTERN);
        config.getAuth().getLdap().setBindDn(OpenLdapContainer.MANAGER_DN);
        config.getAuth().getLdap().setBindPassword(OpenLdapContainer.ADMIN_PASSWORD);
        config.getAuth().getLdap().setGroupSearchBase(OpenLdapContainer.GROUP_SEARCH_BASE);
        config.getAuth().setRoleMappings(Map.of("ADMIN", List.of(OpenLdapContainer.ADMIN_GROUP)));

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
    void loginSucceeds() throws Exception {
        String formBody = "username=" + OpenLdapContainer.TEST_USER + "&password=" + OpenLdapContainer.TEST_PASSWORD;
        var resp = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertNotEquals(401, resp.statusCode(), "Login should succeed");
        assertNotEquals(403, resp.statusCode(), "Login should succeed");
    }

    @Test
    @Order(2)
    void ldapGroupMembershipGrantsConfiguredRole() throws Exception {
        var resp = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/me"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertTrue(
                resp.body().contains("ROLE_ADMIN"),
                "Expected ROLE_ADMIN from LDAP group membership in authorities; got: " + resp.body());
    }

    /**
     * Verifies deny-by-default: when role mappings are configured, a user whose LDAP groups do not match any mapping
     * must be rejected at login.
     */
    @Test
    @Order(3)
    void userNotInMappedGroup_loginDenied() throws Exception {
        // Start a fresh dashboard that maps a *different* group — testuser is not a member of it.
        var config = new GitProxyConfig();
        config.getAuth().setProvider("ldap");
        config.getAuth().getLdap().setUrl(ldap.getLdapUrl());
        config.getAuth().getLdap().setUserDnPatterns(OpenLdapContainer.USER_DN_PATTERN);
        config.getAuth().getLdap().setBindDn(OpenLdapContainer.MANAGER_DN);
        config.getAuth().getLdap().setBindPassword(OpenLdapContainer.ADMIN_PASSWORD);
        config.getAuth().getLdap().setGroupSearchBase(OpenLdapContainer.GROUP_SEARCH_BASE);
        // Map a group the test user is NOT a member of
        config.getAuth().setRoleMappings(Map.of("ADMIN", List.of("no-such-group")));

        try (var restrictedDashboard = new DashboardFixture(config)) {
            var restrictedBaseUrl = restrictedDashboard.getBaseUrl();
            var cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            var restrictedClient = HttpClient.newBuilder()
                    .cookieHandler(cookieManager)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            String formBody =
                    "username=" + OpenLdapContainer.TEST_USER + "&password=" + OpenLdapContainer.TEST_PASSWORD;
            restrictedClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(restrictedBaseUrl + "/login"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            // After a failed login, /api/me must be inaccessible (401 or redirect to login)
            var meResp = restrictedClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(restrictedBaseUrl + "/api/me"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertNotEquals(200, meResp.statusCode(), "User not in mapped group must not access /api/me");
        }
    }
}
