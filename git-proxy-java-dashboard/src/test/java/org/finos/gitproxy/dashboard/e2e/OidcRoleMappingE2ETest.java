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
 * End-to-end tests for OIDC group-claim-to-role mapping.
 *
 * <p>Starts a {@link MockOAuth2Container} and a dashboard configured with {@code auth.role-mappings} and
 * {@code auth.groups-claim}. Drives the full OIDC authorization code flow, injecting a {@code groups} claim via the
 * mock server's custom-claims field. Verifies that the resulting session includes the expected {@code ROLE_ADMIN}
 * authority in {@code /api/me}.
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OidcRoleMappingE2ETest {

    private static final String ADMIN_GROUP = "git-admins";

    static MockOAuth2Container mockOAuth2;
    static DashboardFixture dashboard;
    static String baseUrl;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        mockOAuth2 = new MockOAuth2Container();
        mockOAuth2.start();

        var config = new GitProxyConfig();
        config.getAuth().setProvider("oidc");
        config.getAuth().getOidc().setIssuerUri(mockOAuth2.getIssuerUri());
        config.getAuth().getOidc().setClientId(MockOAuth2Container.CLIENT_ID);
        config.getAuth().getOidc().setClientSecret(MockOAuth2Container.CLIENT_SECRET);
        config.getAuth().setGroupsClaim("groups");
        config.getAuth().setRoleMappings(Map.of("ADMIN", List.of(ADMIN_GROUP)));

        dashboard = new DashboardFixture(config);
        baseUrl = dashboard.getBaseUrl();
    }

    @AfterAll
    static void stopInfrastructure() throws Exception {
        if (dashboard != null) dashboard.close();
        if (mockOAuth2 != null) mockOAuth2.stop();
    }

    @Test
    void oidcGroupClaimGrantsConfiguredRole() throws Exception {
        var cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        // Step 1: Start OIDC flow; follow until the mock server login page (status 200).
        var authorizePageResponse = followUntil200(client, baseUrl + "/oauth2/authorization/gitproxy");
        assertEquals(200, authorizePageResponse.statusCode(), "Expected mock server login page");

        // Step 2: POST username + groups claim. The mock-oauth2-server merges the JSON "claims" field
        // into the id_token, making the groups claim available to the OIDC user service.
        URI authorizeUri = authorizePageResponse.uri();
        String loginBody = "username=" + MockOAuth2Container.TEST_USER + "&claims=%7B%22groups%22%3A%5B%22"
                + ADMIN_GROUP + "%22%5D%7D";

        var loginResp = client.send(
                HttpRequest.newBuilder()
                        .uri(authorizeUri)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(loginBody, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(302, loginResp.statusCode(), "Expected redirect from mock server after login POST");
        String callbackUrl = loginResp.headers().firstValue("Location").orElseThrow();
        assertTrue(callbackUrl.contains("/login/oauth2/code/"), "Expected callback URL; got: " + callbackUrl);

        // Step 3: Dashboard exchanges the auth code, creates a session, redirects to success URL.
        var callbackResp = client.send(
                HttpRequest.newBuilder().uri(URI.create(callbackUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        String callbackLocation = callbackResp.headers().firstValue("Location").orElse("(none)");
        assertEquals(302, callbackResp.statusCode(), "Expected 302 from callback; location=" + callbackLocation);
        assertFalse(callbackLocation.contains("error"), "Callback redirected to error: " + callbackLocation);

        String successUrl = callbackLocation.startsWith("http") ? callbackLocation : baseUrl + callbackLocation;
        client.send(
                HttpRequest.newBuilder().uri(URI.create(successUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        // Step 4: /api/me should include ROLE_ADMIN from the mapped groups claim.
        var meResp = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/me"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, meResp.statusCode(), "Expected 200 for /api/me; body=" + meResp.body());
        assertTrue(
                meResp.body().contains("ROLE_ADMIN"),
                "Expected ROLE_ADMIN from OIDC groups claim in authorities; got: " + meResp.body());
    }

    /**
     * Verifies deny-by-default: when role mappings are configured, a user whose OIDC groups claim does not match any
     * mapping must be rejected — the callback must redirect to an error URL, not establish a session.
     */
    @Test
    void userNotInMappedGroup_loginDenied() throws Exception {
        var cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        var authorizePageResponse = followUntil200(client, baseUrl + "/oauth2/authorization/gitproxy");
        assertEquals(200, authorizePageResponse.statusCode());

        // POST with a groups claim that does NOT match "ADMIN" → ["git-admins"]
        URI authorizeUri = authorizePageResponse.uri();
        String loginBody = "username=" + MockOAuth2Container.TEST_USER
                + "&claims=%7B%22groups%22%3A%5B%22unrelated-group%22%5D%7D";

        var loginResp = client.send(
                HttpRequest.newBuilder()
                        .uri(authorizeUri)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(loginBody, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(302, loginResp.statusCode());
        String callbackUrl = loginResp.headers().firstValue("Location").orElseThrow();

        var callbackResp = client.send(
                HttpRequest.newBuilder().uri(URI.create(callbackUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        // Callback must redirect to an error page, not a success URL
        String location = callbackResp.headers().firstValue("Location").orElse("");
        assertTrue(
                location.contains("error") || callbackResp.statusCode() >= 400,
                "Expected error redirect for unmapped OIDC user; got status="
                        + callbackResp.statusCode() + " location=" + location);

        // /api/me must be inaccessible — no session was established
        var meResp = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/me"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertNotEquals(200, meResp.statusCode(), "Unmapped OIDC user must not access /api/me");
    }

    private static HttpResponse<String> followUntil200(HttpClient client, String startUrl) throws Exception {
        String url = startUrl;
        for (int i = 0; i < 10; i++) {
            var resp = client.send(
                    HttpRequest.newBuilder().uri(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 300 || resp.statusCode() >= 400) return resp;
            String location = resp.headers()
                    .firstValue("Location")
                    .orElseThrow(() -> new AssertionError("3xx without Location: " + resp.uri()));
            if (!location.startsWith("http")) location = baseUrl + location;
            url = location;
        }
        throw new AssertionError("Too many redirects starting from " + startUrl);
    }
}
