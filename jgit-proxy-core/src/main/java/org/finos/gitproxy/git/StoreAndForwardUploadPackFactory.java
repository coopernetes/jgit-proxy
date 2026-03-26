package org.finos.gitproxy.git;

import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.eclipse.jgit.transport.resolver.UploadPackFactory;

/**
 * Factory that creates {@link UploadPack} instances for serving fetches from the local mirror. Fetch freshness is
 * handled by the {@link StoreAndForwardRepositoryResolver} which syncs from upstream before opening.
 */
public class StoreAndForwardUploadPackFactory implements UploadPackFactory<HttpServletRequest> {

    @Override
    public UploadPack create(HttpServletRequest req, Repository db)
            throws ServiceNotEnabledException, ServiceNotAuthorizedException {
        UploadPack up = new UploadPack(db);
        up.setBiDirectionalPipe(false);
        return up;
    }
}
