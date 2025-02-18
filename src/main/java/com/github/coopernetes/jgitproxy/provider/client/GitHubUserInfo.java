package com.github.coopernetes.jgitproxy.provider.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record GitHubUserInfo(
        String login,
        Long id,
        @JsonProperty("node_id") String nodeId,
        String url,
        @JsonProperty("html_url") String htmlUrl,
        @JsonProperty("organizations_url") String organizationsUrl,
        @JsonProperty("repos_url") String reposUrl,
        String type,
        String name,
        String company,
        String location,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {}
