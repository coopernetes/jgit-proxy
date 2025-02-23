package org.finos.gitproxy.git;

import lombok.Data;

/** Represents a single commit. */
@Data
public class Commit {
    private String id;
    private Contributor author;
    private Contributor committer;
    private String message;
    private String date;
}
