package org.finos.gitproxy.git;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Base64;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.provider.GitProxyProvider;

/**
 * Factory that creates {@link ReceivePack} instances for store-and-forward push handling. Extracts credentials from the
 * HTTP request's Basic auth header and wires up the pre/post receive hooks.
 *
 * <p>This factory creates new hook instances per request since each push has its own credentials.
 */
@Slf4j
@RequiredArgsConstructor
public class StoreAndForwardReceivePackFactory implements ReceivePackFactory<HttpServletRequest> {

    private final GitProxyProvider provider;
    private final CommitConfig commitConfig;

    @Override
    public ReceivePack create(HttpServletRequest req, Repository db)
            throws ServiceNotEnabledException, ServiceNotAuthorizedException {

        ReceivePack rp = new ReceivePack(db);
        rp.setBiDirectionalPipe(false);

        // Try request attribute first (set by RepositoryResolver which sees the first request),
        // then fall back to Authorization header on this request
        CredentialsProvider creds =
                (CredentialsProvider) req.getAttribute(StoreAndForwardRepositoryResolver.CREDENTIALS_ATTRIBUTE);
        if (creds == null) {
            creds = extractBasicAuth(req);
        }

        // Per-request validation context shared across validation hooks
        var validationContext = new ValidationContext();

        // Chain hooks:
        // 1. SlowApprovalPreReceiveHook — approval gate with sideband feedback (short-circuits if rejected)
        // 2. AuthorEmailValidationHook — validates emails, writes to context
        // 3. CommitMessageValidationHook — validates messages, writes to context
        // 4. ValidationVerifierHook — reads context, sends summary, rejects if issues
        // 5. ProxyPreReceiveHook — informational commit inspection (only if verified)
        rp.setPreReceiveHook(chainPreReceiveHooks(
                new SlowApprovalPreReceiveHook(),
                new AuthorEmailValidationHook(commitConfig, validationContext),
                new CommitMessageValidationHook(commitConfig, validationContext),
                new ValidationVerifierHook(validationContext),
                new ProxyPreReceiveHook()));
        rp.setPostReceiveHook(new ForwardingPostReceiveHook(provider, creds));

        log.debug("Created ReceivePack for {} with {} auth", provider.getName(), creds != null ? "credentials" : "no");

        return rp;
    }

    private static PreReceiveHook chainPreReceiveHooks(PreReceiveHook... hooks) {
        return (ReceivePack rp, Collection<ReceiveCommand> commands) -> {
            for (PreReceiveHook hook : hooks) {
                hook.onPreReceive(rp, commands);
                // Flush sideband after each hook so messages stream to the client in real time
                // (JGit's sendMessage() doesn't flush — without this, all output batches up)
                try {
                    rp.getMessageOutputStream().flush();
                } catch (IOException e) {
                    log.warn("Failed to flush sideband stream", e);
                }
                // Stop chain if any command was rejected
                if (commands.stream().anyMatch(cmd -> cmd.getResult() != ReceiveCommand.Result.NOT_ATTEMPTED)) {
                    return;
                }
            }
        };
    }

    private CredentialsProvider extractBasicAuth(HttpServletRequest req) {
        String authHeader = req.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            return null;
        }

        try {
            String base64 = authHeader.substring("Basic ".length()).trim();
            String decoded = new String(Base64.getDecoder().decode(base64));
            int colonIndex = decoded.indexOf(':');
            if (colonIndex < 0) {
                log.warn("Invalid Basic auth format (no colon separator)");
                return null;
            }

            String username = decoded.substring(0, colonIndex);
            String password = decoded.substring(colonIndex + 1);
            return new UsernamePasswordCredentialsProvider(username, password);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid Base64 in Authorization header", e);
            return null;
        }
    }
}
