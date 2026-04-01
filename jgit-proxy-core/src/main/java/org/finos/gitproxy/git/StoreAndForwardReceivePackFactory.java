package org.finos.gitproxy.git;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Base64;
import java.util.Collection;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.finos.gitproxy.approval.ApprovalGateway;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.provider.GitProxyProvider;

/**
 * Factory that creates {@link ReceivePack} instances for store-and-forward push handling. Extracts credentials from the
 * HTTP request's Basic auth header and wires up the pre/post receive hooks.
 *
 * <p>This factory creates new hook instances per request since each push has its own credentials.
 */
@Slf4j
public class StoreAndForwardReceivePackFactory implements ReceivePackFactory<HttpServletRequest> {

    private final GitProxyProvider provider;
    private final CommitConfig commitConfig;
    private final PushStore pushStore;
    private final ApprovalGateway approvalGateway;
    private final String serviceUrl;

    public StoreAndForwardReceivePackFactory(
            GitProxyProvider provider,
            CommitConfig commitConfig,
            PushStore pushStore,
            ApprovalGateway approvalGateway) {
        this(provider, commitConfig, pushStore, approvalGateway, null);
    }

    public StoreAndForwardReceivePackFactory(
            GitProxyProvider provider,
            CommitConfig commitConfig,
            PushStore pushStore,
            ApprovalGateway approvalGateway,
            String serviceUrl) {
        this.provider = provider;
        this.commitConfig = commitConfig;
        this.pushStore = pushStore;
        this.approvalGateway = approvalGateway;
        this.serviceUrl = serviceUrl;
    }

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

        // Store the push user in repo config so PushStorePersistenceHook can read it
        String pushUser = (String) req.getAttribute("org.finos.gitproxy.pushUser");
        if (pushUser == null) {
            pushUser = extractUsername(req);
        }
        if (pushUser != null) {
            db.getConfig().setString("gitproxy", null, "pushUser", pushUser);
        }

        // Per-request shared contexts
        var validationContext = new ValidationContext();
        var pushContext = new PushContext();

        // Persistence hook (records push to database)
        var persistenceHook = pushStore != null ? new PushStorePersistenceHook(pushStore, provider) : null;
        if (persistenceHook != null) {
            persistenceHook.setPushContext(pushContext);
        }

        // Hook chain — order matters:
        //
        // 0. PushStorePersistenceHook.preReceive  — record RECEIVED (no steps yet)
        // 1. AuthorEmailValidationHook             — validates emails; records "checkAuthorEmails" step
        // 2. CommitMessageValidationHook           — validates messages; records "checkCommitMessages" step
        // 3. ProxyPreReceiveHook                   — commit inspection; records "inspection" step
        // 4. DiffGenerationHook                    — generates diffs; records "diff" / "diff:default-branch" steps
        // 5. DiffScanningHook                      — scans diff added-lines for blocked content; records "scanDiff"
        // step
        // 6. PushStorePersistenceHook.validationResult — saves APPROVED or BLOCKED record with all steps so far
        // 7. ApprovalPreReceiveHook                — blocks until reviewer approves/rejects or timeout
        //
        // Post-receive (only runs when pre-receive doesn't stop the chain):
        // 8. ForwardingPostReceiveHook             — forwards to upstream; records "forward" step
        // 9. PushStorePersistenceHook.postReceive  — saves FORWARDED or ERROR record with forwarding step

        PreReceiveHook[] preHooks;
        if (persistenceHook != null) {
            preHooks = new PreReceiveHook[] {
                persistenceHook.preReceiveHook(),
                new AuthorEmailValidationHook(commitConfig, validationContext, pushContext),
                new CommitMessageValidationHook(commitConfig, validationContext, pushContext),
                new ProxyPreReceiveHook(pushContext),
                new DiffGenerationHook(pushContext),
                new DiffScanningHook(commitConfig, validationContext, pushContext),
                persistenceHook.validationResultHook(validationContext),
                new ApprovalPreReceiveHook(pushStore, approvalGateway, serviceUrl)
            };
        } else {
            preHooks = new PreReceiveHook[] {
                new AuthorEmailValidationHook(commitConfig, validationContext, pushContext),
                new CommitMessageValidationHook(commitConfig, validationContext, pushContext),
                new ProxyPreReceiveHook(pushContext),
                new DiffGenerationHook(pushContext),
                new DiffScanningHook(commitConfig, validationContext, pushContext)
            };
        }
        rp.setPreReceiveHook(chainPreReceiveHooks(preHooks));

        // Post-receive: forward to upstream, then record final status
        var forwardingHook = new ForwardingPostReceiveHook(provider, creds, pushContext);
        if (persistenceHook != null) {
            rp.setPostReceiveHook(chainPostReceiveHooks(forwardingHook, persistenceHook.postReceiveHook()));
        } else {
            rp.setPostReceiveHook(forwardingHook);
        }

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

    private static PostReceiveHook chainPostReceiveHooks(PostReceiveHook... hooks) {
        return (ReceivePack rp, Collection<ReceiveCommand> commands) -> {
            for (PostReceiveHook hook : hooks) {
                hook.onPostReceive(rp, commands);
            }
        };
    }

    private CredentialsProvider extractBasicAuth(HttpServletRequest req) {
        String[] userPass = extractUserPass(req);
        if (userPass == null) return null;
        return new UsernamePasswordCredentialsProvider(userPass[0], userPass[1]);
    }

    private String extractUsername(HttpServletRequest req) {
        String[] userPass = extractUserPass(req);
        return userPass != null ? userPass[0] : null;
    }

    private String[] extractUserPass(HttpServletRequest req) {
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
            return new String[] {decoded.substring(0, colonIndex), decoded.substring(colonIndex + 1)};
        } catch (IllegalArgumentException e) {
            log.warn("Invalid Base64 in Authorization header", e);
            return null;
        }
    }
}
