package org.finos.gitproxy.git;

import lombok.Data;

/** A generic class that represents a contributor to a git repository. It can either be an author or a committer. */
@Data
public class Contributor {
    private String name;
    private String email;
}
