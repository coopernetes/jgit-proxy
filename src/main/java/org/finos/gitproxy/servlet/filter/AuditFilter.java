package org.finos.gitproxy.servlet.filter;

public interface AuditFilter extends GitProxyFilter {

    void audit(String message);
}
