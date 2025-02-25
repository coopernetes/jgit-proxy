package org.finos.gitproxy.git;

import java.time.Instant;

import lombok.Builder;
import lombok.Data;

/** Represents a single commit. */
@Data
@Builder
public class Commit {
    private String sha;
    private String parent;
    private Contributor author;
    private Contributor committer;
    private String message;
    private Instant date;
    private String signature;
}
