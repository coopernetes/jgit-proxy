package org.finos.gitproxy.git;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    /** Trailers matching {@code Signed-off-by: Name <email>}, in order of appearance. */
    @Builder.Default
    private List<String> signedOffBy = new ArrayList<>();
}
