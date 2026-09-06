package com.rbc.fogwall.db.model;

import lombok.Builder;
import lombok.Data;

/**
 * Query parameters for filtering SCM API action records. All fields are optional; null means "don't filter on this".
 */
@Data
@Builder
public class ScmApiActionQuery {
    private ScmApiActionStatus status;
    private String provider;
    private String user;
    private String repoOwner;
    private String repoName;

    /** Free-text search: matches records where repo owner OR repo name contains this value (case-insensitive LIKE). */
    private String search;

    /** Maximum number of results to return. */
    @Builder.Default
    private int limit = 100;

    /** Number of results to skip (for pagination). */
    @Builder.Default
    private int offset = 0;

    /** Order results by timestamp descending (newest first). Defaults to true. */
    @Builder.Default
    private boolean newestFirst = true;
}
