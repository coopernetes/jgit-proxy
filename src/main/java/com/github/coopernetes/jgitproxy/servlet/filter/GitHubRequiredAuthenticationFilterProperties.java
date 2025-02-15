package com.github.coopernetes.jgitproxy.servlet.filter;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class GitHubRequiredAuthenticationFilterProperties extends FilterProperties {
    private boolean requireBearer = false;
    private boolean requireBasic = false;
}
