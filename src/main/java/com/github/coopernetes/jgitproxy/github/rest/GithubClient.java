package com.github.coopernetes.jgitproxy.github.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class GithubClient {

    private final ObjectMapper objectMapper;

    public Optional<GithubUserInfo> getUserInfo(String token) {
        try (var httpClient = HttpClients.createDefault()) {
            var request = new HttpGet("https://api.github.com/user");
            request.setHeader("Authorization", "Bearer " + token);
            try (var response = httpClient.execute(request)) {
                if (response.getStatusLine().getStatusCode() == 200) {
                    var result = objectMapper.readValue(response.getEntity().getContent(), GithubUserInfo.class);
                    log.debug("user={}", result);
                    return Optional.of(result);
                } else {
                    log.error(
                            "Failed to fetch user info: {}",
                            response.getStatusLine().getReasonPhrase());
                    return Optional.empty();
                }
            } catch (IOException e) {
                log.error("Exception occurred while fetching user info", e);
                return Optional.empty();
            }
        } catch (IOException e) {
            log.error("Exception occurred while fetching user info", e);
            return Optional.empty();
        }
    }
}
