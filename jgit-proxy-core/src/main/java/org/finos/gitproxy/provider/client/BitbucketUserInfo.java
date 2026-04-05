package org.finos.gitproxy.provider.client;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Jackson deserialization target for the Bitbucket Cloud {@code GET /2.0/user} response.
 *
 * <p>The {@code username} field is Bitbucket's internal, URL-safe identifier (e.g.
 * {@code a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6}) which is not the same as the email or display name. The {@code account_id}
 * is the most stable identifier and is what should be stored in {@code user_scm_identities} for Bitbucket users.
 */
public record BitbucketUserInfo(
        @JsonProperty("display_name") String displayName,
        @JsonProperty String username,
        @JsonProperty("account_id") String accountId,
        @JsonProperty String nickname) {}
