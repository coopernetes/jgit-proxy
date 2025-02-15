package com.github.coopernetes.jgitproxy.provider.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;

@Slf4j
@RequiredArgsConstructor
public class GithubRestClient {

    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public Optional<GithubUserInfo> getUserInfo(String authHeader) {
        try (var httpClient = HttpClients.createDefault()) {
            var request = new HttpGet(baseUrl + "/user");
            request.setHeader("Authorization", authHeader);
            try (var response = httpClient.execute(request)) {
                if (response.getStatusLine().getStatusCode() == 200) {
                    var result = objectMapper.readValue(response.getEntity().getContent(), GithubUserInfo.class);
                    log.debug("user={}", result);
                    return Optional.of(result);
                }
                log.error(
                        "Failed to fetch user info: {} {}",
                        response.getStatusLine().getReasonPhrase(),
                        response.getEntity().getContent());
            } catch (IOException e) {
                log.error("Exception occurred while fetching user info", e);
            }
        } catch (IOException e) {
            log.error("Exception occurred while setting up Github client", e);
        }
        return Optional.empty();
    }
}
