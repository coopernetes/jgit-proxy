package org.finos.gitproxy.git;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A generic class that represents a contributor to a git repository. It can either be an author or a committer. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Contributor {
    private String name;
    private String email;
}
