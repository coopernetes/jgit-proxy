package org.finos.gitproxy.provider.client;

/** Jackson deserialization target for the GitLab {@code GET /api/v4/user} response. */
public record GitLabUserInfo(
        String username,
        Long id,
        // GitLab's API seems to return the default email address even if profile settings are set to "Do not show on
        // profile". This needs to be verified with more testing. This was tested with one account and the email
        // returned in the response was the primary email (not any secondary or committer emails).
        String email) {}
