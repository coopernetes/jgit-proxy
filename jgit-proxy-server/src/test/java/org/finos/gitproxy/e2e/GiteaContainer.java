package org.finos.gitproxy.e2e;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Testcontainers wrapper for a Gitea instance used as the upstream git server in e2e tests.
 *
 * <p>Starts Gitea with the install lock set (skips the setup wizard), creates an admin user via the Gitea CLI, and
 * initialises a public test repository ({@value #TEST_ORG}/{@value #TEST_REPO}) via the Gitea REST API.
 */
@SuppressWarnings("resource")
class GiteaContainer extends GenericContainer<GiteaContainer> {

    static final String ADMIN_USER = "gitproxyadmin";
    static final String ADMIN_PASSWORD = "Admin1234!";
    static final String ADMIN_EMAIL = "admin@example.com";
    static final String TEST_ORG = "test-owner";
    static final String TEST_REPO = "test-repo";

    /** Valid author identity for commits that should pass validation. */
    static final String VALID_AUTHOR_NAME = "Test User";

    static final String VALID_AUTHOR_EMAIL = "testuser@example.com";

    private static final int HTTP_PORT = 3000;

    GiteaContainer() {
        super("gitea/gitea:1.22");
        withEnv("GITEA__security__INSTALL_LOCK", "true");
        withEnv("GITEA__security__SECRET_KEY", "e2e-test-secret-key-not-for-production");
        withEnv("GITEA__server__HTTP_PORT", String.valueOf(HTTP_PORT));
        withEnv("GITEA__database__DB_TYPE", "sqlite3");
        withEnv("GITEA__log__LEVEL", "Warn");
        withEnv("GITEA__repository__DEFAULT_BRANCH", "main");
        withExposedPorts(HTTP_PORT);
        waitingFor(Wait.forHttp("/api/healthz").forPort(HTTP_PORT).forStatusCode(200));
    }

    /** Base URL of the Gitea instance as seen from the host (e.g. {@code http://localhost:32768}). */
    String getBaseUrl() {
        return "http://" + getHost() + ":" + getMappedPort(HTTP_PORT);
    }

    URI getBaseUri() {
        return URI.create(getBaseUrl());
    }

    /**
     * Creates the admin user by running the Gitea CLI inside the container. Must be called after the container has
     * started.
     *
     * <p>Uses {@code su-exec} (Alpine's privilege-dropper, always present in the Gitea image) to run as the {@code git}
     * user — the Gitea binary refuses to start as root.
     */
    void createAdminUser() throws IOException, InterruptedException {
        var result = execInContainer(
                "/sbin/su-exec",
                "git",
                "gitea",
                "admin",
                "user",
                "create",
                "--admin",
                "--username",
                ADMIN_USER,
                "--password",
                ADMIN_PASSWORD,
                "--email",
                ADMIN_EMAIL,
                "--must-change-password=false");
        if (result.getExitCode() != 0 && !result.getStderr().contains("already exists")) {
            throw new RuntimeException(
                    "Failed to create Gitea admin user: " + result.getStderr() + " / stdout: " + result.getStdout());
        }
    }

    /**
     * Creates the test organisation and a public repository via the Gitea REST API. The repo is auto-initialised with a
     * README so it has a {@code main} branch ready for pushes. Must be called after {@link #createAdminUser()}.
     */
    void createTestRepo() throws IOException, InterruptedException {
        var client = HttpClient.newHttpClient();
        String auth = Base64.getEncoder().encodeToString((ADMIN_USER + ":" + ADMIN_PASSWORD).getBytes());
        String base = getBaseUrl();

        // Create organisation (ignore 422 if it already exists)
        apiPost(client, auth, base + "/api/v1/orgs", "{\"username\":\"" + TEST_ORG + "\",\"visibility\":\"public\"}");

        // Create repository under the org (auto_init gives an initial commit on main)
        var resp = apiPost(
                client,
                auth,
                base + "/api/v1/orgs/" + TEST_ORG + "/repos",
                "{\"name\":\"" + TEST_REPO + "\",\"private\":false,\"auto_init\":true,\"default_branch\":\"main\"}");
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("Failed to create test repo (" + resp.statusCode() + "): " + resp.body());
        }
    }

    private HttpResponse<String> apiPost(HttpClient client, String auth, String url, String body)
            throws IOException, InterruptedException {
        var req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + auth)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
