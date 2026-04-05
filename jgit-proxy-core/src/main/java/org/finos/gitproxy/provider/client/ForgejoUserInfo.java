package org.finos.gitproxy.provider.client;

/** Jackson deserialization target for the Forgejo/Gitea {@code GET /api/v1/user} response. */
public record ForgejoUserInfo(String login, Long id, String email) {}
