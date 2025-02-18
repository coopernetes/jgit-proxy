package com.github.coopernetes.jgitproxy.provider.client;

public interface GitHubApi {

    String getApiUrl();

    String getGraphqlUrl();

    GitHubClient getRestClient();
}
