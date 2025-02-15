package com.github.coopernetes.jgitproxy.provider.client;

public interface GithubApi {

    String getApiUrl();

    String getGraphqlUrl();

    GithubRestClient getRestClient();
}
