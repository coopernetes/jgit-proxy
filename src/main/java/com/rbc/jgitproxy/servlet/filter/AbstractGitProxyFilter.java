package com.rbc.jgitproxy.servlet.filter;

import com.rbc.jgitproxy.git.HttpOperation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.function.Predicate;

@RequiredArgsConstructor
@Slf4j
public abstract class AbstractGitProxyFilter implements GitProxyFilter {
    protected final int order;
    protected final Set<HttpOperation> applicableOperations;

    @Override
    public int getOrder() {
        return this.order;
    }

    @Override
    public Predicate<HttpServletRequest> shouldFilter() {
        return (HttpServletRequest request) -> applicableOperations.contains(determineOperation(request));
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + "{" + "order="
                + order + ", appliedOperations="
                + applicableOperations + '}';
    }
}
