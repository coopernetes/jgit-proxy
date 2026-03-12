package org.finos.gitproxy.jetty.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.provider.client.GitHubUserClient;
import org.finos.gitproxy.provider.client.GitHubUserInfo;

/**
 * Lightweight implementation of {@link GitHubUserClient} using the JDK {@link HttpClient} for use with the standalone
 * Jetty server (no Spring dependencies).
 */
@Slf4j
public class JettyGitHubUserClient implements GitHubUserClient {

    public static final String MEDIA_TYPE = "application/vnd.github+json";
    public static final String API_VERSION_HEADER = "X-GitHub-Api-Version";
    public static final String API_VERSION = "2022-11-28";

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public JettyGitHubUserClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public Optional<GitHubUserInfo> getUserInfo(String authHeader) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/user"))
                    .header("Authorization", authHeader)
                    .header("Accept", MEDIA_TYPE)
                    .header(API_VERSION_HEADER, API_VERSION)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Optional.ofNullable(objectMapper.readValue(response.body(), GitHubUserInfo.class));
            } else {
                log.warn("GitHub API request failed with status {}", response.statusCode());
                return Optional.empty();
            }
        } catch (Exception e) {
            log.warn("GitHub API request failed", e);
            return Optional.empty();
        }
    }
}
