package com.rbc.jgitproxy.servlet.filter;

import com.rbc.jgitproxy.git.HttpOperation;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
