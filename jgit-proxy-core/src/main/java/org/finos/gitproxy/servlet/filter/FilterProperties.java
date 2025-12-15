package org.finos.gitproxy.servlet.filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.finos.gitproxy.git.HttpOperation;

/** A set of common configuration properties that configure the behavior of a filter. */
@Getter
@Setter
@ToString
public class FilterProperties {
    private boolean enabled = false;
    private int order = 0;
    private Set<HttpOperation> operations;
    private List<String> providers = new ArrayList<>();
}
