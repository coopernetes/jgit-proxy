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
import org.finos.gitproxy.config.GpgConfig;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.service.UserAuthorizationService;

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
    private final GpgConfig gpgConfig;
    private final UserAuthorizationService userAuthorizationService;
    private final PushStore pushStore;
    private final ApprovalGateway approvalGateway;
    private final String serviceUrl;

    public StoreAndForwardReceivePackFactory(
            GitProxyProvider provider,
            CommitConfig commitConfig,
            PushStore pushStore,
            ApprovalGateway approvalGateway) {
        this(provider, commitConfig, GpgConfig.defaultConfig(), null, pushStore, approvalGateway, null);
    }

    public StoreAndForwardReceivePackFactory(
            GitProxyProvider provider,
            CommitConfig commitConfig,
            PushStore pushStore,
            ApprovalGateway approvalGateway,
            String serviceUrl) {
        this(provider, commitConfig, GpgConfig.defaultConfig(), null, pushStore, approvalGateway, serviceUrl);
    }

    public StoreAndForwardReceivePackFactory(
            GitProxyProvider provider,
            CommitConfig commitConfig,
            GpgConfig gpgConfig,
            UserAuthorizationService userAuthorizationService,
            PushStore pushStore,
            ApprovalGateway approvalGateway,
            String serviceUrl) {
        this.provider = provider;
        this.commitConfig = commitConfig;
        this.gpgConfig = gpgConfig != null ? gpgConfig : GpgConfig.defaultConfig();
        this.userAuthorizationService = userAuthorizationService;
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
            persistenceHook.setServiceUrl(serviceUrl);
            persistenceHook.setAutoApproval(approvalGateway != null && approvalGateway.approvesImmediately());
        }

        // Hook chain - order matters:
        //
        // 0. PushStorePersistenceHook.preReceive      - record RECEIVED (no steps yet)
        // 1. RepositoryWhitelistHook         (1000)   - record whitelist PASS (resolver already validated)
        // 2. CheckUserPushPermissionHook     (2000)   - validates push user authorization
        // 3. CheckEmptyBranchHook            (2050)   - reject if no commits introduced (short-circuit)
        // 4. CheckHiddenCommitsHook          (2060)   - reject if pack contains commits outside push range
        // 5. AuthorEmailValidationHook       (2100)   - validates emails; records "checkAuthorEmails" step
        // 6. CommitMessageValidationHook     (2200)   - validates messages; records "checkCommitMessages" step
        // 7. ProxyPreReceiveHook                      - commit inspection; records "inspection" step
        // 8. DiffGenerationHook                       - generates diffs; records "diff" / "diff:default-branch" steps
        // 9. DiffScanningHook                (2300)   - scans diff added-lines for blocked content; records "scanDiff"
        // 10. GpgSignatureHook               (2400)   - checks GPG signatures; records "GpgSignatureHook" step
        // 11. SecretScanningHook             (2500)   - pipes diff to gitleaks; records "scanSecrets" step
        // 12. PushStorePersistenceHook.validationResult - saves APPROVED or BLOCKED record with all steps so far
        // 13. ApprovalPreReceiveHook                  - blocks until reviewer approves/rejects or timeout
        //
        // Post-receive (only runs when pre-receive doesn't stop the chain):
        // 14. ForwardingPostReceiveHook               - forwards to upstream; records "forward" step
        // 15. PushStorePersistenceHook.postReceive    - saves FORWARDED or ERROR record with forwarding step

        var permissionHook = new CheckUserPushPermissionHook(
                userAuthorizationService != null
                        ? userAuthorizationService
                        : new org.finos.gitproxy.service.DummyUserAuthorizationService(),
                validationContext,
                pushContext);

        PreReceiveHook[] preHooks;
        if (persistenceHook != null) {
            preHooks = new PreReceiveHook[] {
                persistenceHook.preReceiveHook(),
                new RepositoryWhitelistHook(pushContext),
                permissionHook,
                new CheckEmptyBranchHook(pushContext),
                new CheckHiddenCommitsHook(pushContext),
                new AuthorEmailValidationHook(commitConfig, validationContext, pushContext),
                new CommitMessageValidationHook(commitConfig, validationContext, pushContext),
                new ProxyPreReceiveHook(pushContext),
                new DiffGenerationHook(pushContext),
                new DiffScanningHook(commitConfig, validationContext, pushContext),
                new GpgSignatureHook(gpgConfig, validationContext, pushContext),
                new SecretScanningHook(commitConfig.getSecretScanning(), validationContext, pushContext),
                persistenceHook.validationResultHook(validationContext),
                new ApprovalPreReceiveHook(pushStore, approvalGateway, serviceUrl)
            };
        } else {
            preHooks = new PreReceiveHook[] {
                new RepositoryWhitelistHook(pushContext),
                permissionHook,
                new CheckEmptyBranchHook(pushContext),
                new CheckHiddenCommitsHook(pushContext),
                new AuthorEmailValidationHook(commitConfig, validationContext, pushContext),
                new CommitMessageValidationHook(commitConfig, validationContext, pushContext),
                new ProxyPreReceiveHook(pushContext),
                new DiffGenerationHook(pushContext),
                new DiffScanningHook(commitConfig, validationContext, pushContext),
                new GpgSignatureHook(gpgConfig, validationContext, pushContext),
                new SecretScanningHook(commitConfig.getSecretScanning(), validationContext, pushContext)
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
                // (JGit's sendMessage() doesn't flush - without this, all output batches up)
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
