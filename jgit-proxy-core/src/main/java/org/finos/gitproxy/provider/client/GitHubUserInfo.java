package org.finos.gitproxy.provider.client;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.util.Optional;

/** Jackson deserialization target for the GitHub {@code GET /user} response. */
public record GitHubUserInfo(
        String login,
        Long id,
        // A user has to configure their profile explicitly to have a publicly visible email
        // for the value to be returned by the API. By most cases, it is null (Optional.empty()) due
        // to the default visibility of email being private.
        @JsonSetter(nulls = Nulls.AS_EMPTY) Optional<String> email) {}
