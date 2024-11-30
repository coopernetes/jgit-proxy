package com.github.coopernetes.jgitproxy.github.rest;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class GithubUserInfo {
    private String login;
    private String type;
    private String name;
    private String company;
    private String email = null; // default for most users

    @JsonProperty("created_at")
    private Instant createdAt;

    @JsonProperty("updated_at")
    private Instant updatedAt;
}
