package org.finos.gitproxy.permission;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single authorization grant: {@link #username} is permitted to perform {@link #operations} on repos matching
 * {@link #path} at {@link #provider}.
 *
 * <p>{@link #path} is a pattern of the form {@code /owner/repo}. {@link #pathType} controls how it is matched:
 * {@code LITERAL} for exact equality, {@code GLOB} for {@code *}/{@code ?} wildcards, {@code REGEX} for full Java regex
 * matched against the path string.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RepoPermission {

    @Builder.Default
    private String id = UUID.randomUUID().toString();

    private String username;
    private String provider;
    private String path;

    @Builder.Default
    private PathType pathType = PathType.LITERAL;

    @Builder.Default
    private Operations operations = Operations.ALL;

    @Builder.Default
    private Source source = Source.DB;

    public enum PathType {
        LITERAL,
        GLOB,
        REGEX
    }

    public enum Operations {
        PUSH,
        APPROVE,
        ALL
    }

    public enum Source {
        CONFIG,
        DB
    }
}
