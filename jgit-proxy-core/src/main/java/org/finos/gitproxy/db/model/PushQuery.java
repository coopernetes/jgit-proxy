package org.finos.gitproxy.db.model;

import lombok.Builder;
import lombok.Data;

/** Query parameters for filtering push records. All fields are optional; null means "don't filter on this". */
@Data
@Builder
public class PushQuery {
    private PushStatus status;
    private String project;
    private String repoName;
    private String branch;
    private String user;
    private String authorEmail;
    private String commitTo;

    /** Maximum number of results to return. */
    @Builder.Default
    private int limit = 100;

    /** Order results by timestamp descending (newest first). Defaults to true. */
    @Builder.Default
    private boolean newestFirst = true;
}
