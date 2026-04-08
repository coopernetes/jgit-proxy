package org.finos.gitproxy.db.model;

import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

/**
 * A single access control rule governing which repositories may be fetched from or pushed to through the proxy. Rules
 * are evaluated by {@code UrlAccessControlFilter} (see #60).
 *
 * <p>The {@code owner}, {@code name}, and {@code slug} fields use the standard {@code {owner}/{repo}} URL shape shared
 * by GitHub, GitLab, Gitea, Codeberg, and Forgejo. Generic providers with arbitrary URL paths should use {@code slug}
 * with a glob pattern and leave {@code owner}/{@code name} null.
 */
@Data
@Builder
@Jacksonized
public class AccessRule {

    @Builder.Default
    private String id = UUID.randomUUID().toString();

    /** Provider name this rule applies to. Null = applies to all providers. */
    private String provider;

    /** Glob pattern matching {@code /owner/repo} slug. Null = not used. */
    private String slug;

    /** Glob pattern matching the owner/org portion of the URL. Null = not used. */
    private String owner;

    /** Glob pattern matching the repository name portion of the URL. Null = not used. */
    private String name;

    /** Whether this rule allows or denies matched repositories. */
    @Builder.Default
    private Access access = Access.ALLOW;

    /** Which git operations this rule applies to. */
    @Builder.Default
    private Operations operations = Operations.ALL;

    private String description;

    @Builder.Default
    private boolean enabled = true;

    @Builder.Default
    private int ruleOrder = 100;

    /**
     * Whether this rule was seeded from YAML configuration ({@code CONFIG}) or created via the REST API ({@code DB}).
     */
    @Builder.Default
    private Source source = Source.DB;

    public enum Access {
        ALLOW,
        DENY
    }

    public enum Operations {
        FETCH,
        PUSH,
        ALL
    }

    public enum Source {
        CONFIG,
        DB
    }
}
