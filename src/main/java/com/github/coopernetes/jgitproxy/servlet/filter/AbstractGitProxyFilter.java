package com.github.coopernetes.jgitproxy.servlet.filter;

import com.github.coopernetes.jgitproxy.git.HttpOperation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@Slf4j
public abstract class AbstractGitProxyFilter implements GitProxyFilter {
    protected final int order;
    protected final Set<HttpOperation> appliedOperations;

    @Override
    public int getOrder() {
        return this.order;
    }

    @Override
    public boolean shouldFilter(HttpServletRequest request) {
        return appliedOperations.contains(determineOperation(request));
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + "{" + "order="
                + order + ", appliedOperations="
                + appliedOperations + '}';
    }
}
