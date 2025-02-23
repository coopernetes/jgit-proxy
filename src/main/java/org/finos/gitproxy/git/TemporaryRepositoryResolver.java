package org.finos.gitproxy.git;

import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;

public class TemporaryRepositoryResolver implements RepositoryResolver<HttpServletRequest> {

    @Override
    public Repository open(HttpServletRequest req, String name)
            throws RepositoryNotFoundException, ServiceNotAuthorizedException, ServiceNotEnabledException,
                    ServiceMayNotContinueException {
        return null;
    }
}
