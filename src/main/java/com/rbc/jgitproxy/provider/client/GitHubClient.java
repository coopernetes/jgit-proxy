package com.rbc.jgitproxy.provider.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class GitHubClient {

    public static final String MEDIA_TYPE = "application/vnd.github+json";
    public static final String API_VERSION_HEADER = "X-GitHub-Api-Version";
    public static final String API_VERSION = "2022-11-28";

    private final String baseUrl;

    public Optional<GitHubUserInfo> getUserInfo(String authHeader) {
        try {
            return Optional.ofNullable(restClient()
                    .get()
                    .uri("/user")
                    .header("Authorization", authHeader)
                    .header(HttpHeaders.ACCEPT, MEDIA_TYPE)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        log.warn("Failed to get user info from GitHub: {}", response.getStatusCode());
                        throw new RestClientResponseException(
                                "GitHub API request failed",
                                response.getStatusCode().value(),
                                response.getStatusText(),
                                response.getHeaders(),
                                response.getBody().readAllBytes(),
                                null);
                    })
                    .body(GitHubUserInfo.class));
        } catch (RestClientResponseException e) {
            log.warn("GitHub API request failed with status {}", e.getStatusCode(), e);
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("GitHub API request failed", e);
            return Optional.empty();
        }
    }

    private RestClient restClient() {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, MEDIA_TYPE)
                .defaultHeader(API_VERSION_HEADER, API_VERSION)
                .build();
    }
}
