package org.finos.gitproxy.git;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
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
import org.finos.gitproxy.provider.BitbucketProvider;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.service.PushIdentityResolver;
import org.finos.gitproxy.service.UserAuthorizationService;

/**
 * Factory that creates {@link ReceivePack} instances for store-and-forward push handling. Extracts credentials from the
 * HTTP request's Basic auth header and wires up the pre/post receive hooks.
 *
 * <p>This factory creates new hook instances per request since each push has its own credentials.
 */
@Slf4j
public class StoreAndForwardReceivePackFactory implements ReceivePackFactory<HttpServletRequest> {

    private static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(10);

    private final GitProxyProvider provider;
    private final CommitConfig commitConfig;
    private final GpgConfig gpgConfig;
    private final UserAuthorizationService userAuthorizationService;
    private final PushIdentityResolver pushIdentityResolver;
    private final PushStore pushStore;
    private final ApprovalGateway approvalGateway;
    private final String serviceUrl;
    private final Duration heartbeatInterval;

    public StoreAndForwardReceivePackFactory(
            GitProxyProvider provider,
            CommitConfig commitConfig,
            PushStore pushStore,
            ApprovalGateway approvalGateway) {
        this(
                provider,
                commitConfig,
                GpgConfig.defaultConfig(),
                null,
                null,
                pushStore,
                approvalGateway,
                null,
                DEFAULT_HEARTBEAT_INTERVAL);
    }

    public StoreAndForwardReceivePackFactory(
            GitProxyProvider provider,
            CommitConfig commitConfig,
            PushStore pushStore,
            ApprovalGateway approvalGateway,
            String serviceUrl) {
        this(
                provider,
                commitConfig,
                GpgConfig.defaultConfig(),
                null,
                null,
                pushStore,
                approvalGateway,
                serviceUrl,
                DEFAULT_HEARTBEAT_INTERVAL);
    }

    public StoreAndForwardReceivePackFactory(
            GitProxyProvider provider,
            CommitConfig commitConfig,
            GpgConfig gpgConfig,
            UserAuthorizationService userAuthorizationService,
            PushIdentityResolver pushIdentityResolver,
            PushStore pushStore,
            ApprovalGateway approvalGateway,
            String serviceUrl) {
        this(
                provider,
                commitConfig,
                gpgConfig,
                userAuthorizationService,
                pushIdentityResolver,
                pushStore,
                approvalGateway,
                serviceUrl,
                DEFAULT_HEARTBEAT_INTERVAL);
    }

    public StoreAndForwardReceivePackFactory(
            GitProxyProvider provider,
            CommitConfig commitConfig,
            GpgConfig gpgConfig,
            UserAuthorizationService userAuthorizationService,
            PushIdentityResolver pushIdentityResolver,
            PushStore pushStore,
            ApprovalGateway approvalGateway,
            String serviceUrl,
            Duration heartbeatInterval) {
        this.provider = provider;
        this.commitConfig = commitConfig;
        this.gpgConfig = gpgConfig != null ? gpgConfig : GpgConfig.defaultConfig();
        this.userAuthorizationService = userAuthorizationService;
        this.pushIdentityResolver = pushIdentityResolver;
        this.pushStore = pushStore;
        this.approvalGateway = approvalGateway;
        this.serviceUrl = serviceUrl;
        this.heartbeatInterval = heartbeatInterval != null ? heartbeatInterval : DEFAULT_HEARTBEAT_INTERVAL;
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

        // Store push credentials in repo config so hooks can read them for identity resolution.
        // pushToken is the PAT/password — stored only in-process repo config, never persisted to disk.
        String pushUser = (String) req.getAttribute("org.finos.gitproxy.pushUser");
        String pushToken = null;
        if (pushUser == null) {
            String[] userPass = extractUserPass(req);
            if (userPass != null) {
                pushUser = userPass[0];
                pushToken = userPass[1];
            }
        }
        if (pushUser != null) {
            db.getConfig().setString("gitproxy", null, "pushUser", pushUser);
        }
        if (pushToken != null) {
            db.getConfig().setString("gitproxy", null, "pushToken", pushToken);
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

        // Orderable validation hooks - sorted by getOrder() before chaining.
        // Lifecycle hooks (persistence, approval) are pinned outside this list.
        //
        // Authorization range (0-199):
        //   RepositoryWhitelistHook         (100) - whitelist PASS (resolver already validated)
        //   CheckUserPushPermissionHook     (150) - push user authorization
        // Content filtering range (200-399):
        //   CheckEmptyBranchHook            (210) - reject if no commits introduced (short-circuit)
        //   CheckHiddenCommitsHook          (220) - reject if pack contains commits outside push range
        //   AuthorEmailValidationHook       (250) - validates emails
        //   CommitMessageValidationHook     (260) - validates messages
        //   ProxyPreReceiveHook             (270) - commit inspection
        //   DiffGenerationHook              (280) - generates diffs for scanning and persistence
        //   DiffScanningHook                (300) - scans diff added-lines for blocked content
        //   GpgSignatureHook                (320) - checks GPG signatures
        //   SecretScanningHook              (340) - pipes diff to gitleaks
        //
        // Pinned lifecycle hooks (not orderable):
        //   [pre]  PushStorePersistenceHook.preReceive      - record RECEIVED
        //   [post-validation] PushStorePersistenceHook.validationResult - save APPROVED/BLOCKED
        //   [post-validation] ApprovalPreReceiveHook        - blocks until approved or timeout
        //
        // Post-receive:
        //   ForwardingPostReceiveHook       - forwards to upstream
        //   PushStorePersistenceHook.postReceive - save FORWARDED/ERROR

        var permissionHook = new CheckUserPushPermissionHook(
                pushIdentityResolver,
                userAuthorizationService != null
                        ? userAuthorizationService
                        : new org.finos.gitproxy.service.DummyUserAuthorizationService(),
                validationContext,
                pushContext,
                provider);

        var identityVerificationHook = new IdentityVerificationHook(
                pushIdentityResolver, commitConfig.getIdentityVerification(), validationContext, pushContext, provider);

        // Build and sort the orderable validation hook list
        List<GitProxyHook> validationHooks = new ArrayList<>(List.of(
                new RepositoryWhitelistHook(pushContext),
                permissionHook,
                identityVerificationHook,
                new CheckEmptyBranchHook(pushContext),
                new CheckHiddenCommitsHook(pushContext),
                new AuthorEmailValidationHook(commitConfig, validationContext, pushContext),
                new CommitMessageValidationHook(commitConfig, validationContext, pushContext),
                new ProxyPreReceiveHook(pushContext),
                new DiffGenerationHook(pushContext),
                new DiffScanningHook(commitConfig, validationContext, pushContext),
                new GpgSignatureHook(gpgConfig, validationContext, pushContext),
                new SecretScanningHook(commitConfig.getSecretScanning(), validationContext, pushContext)));
        if (provider instanceof BitbucketProvider bitbucketProvider) {
            validationHooks.add(new BitbucketCredentialRewriteHook(bitbucketProvider));
        }
        validationHooks.sort(Comparator.comparingInt(GitProxyHook::getOrder));

        PreReceiveHook[] preHooks;
        if (persistenceHook != null) {
            List<PreReceiveHook> hooks = new ArrayList<>();
            hooks.add(persistenceHook.preReceiveHook());
            hooks.addAll(validationHooks);
            hooks.add(persistenceHook.validationResultHook(validationContext));
            hooks.add(new ApprovalPreReceiveHook(pushStore, approvalGateway, serviceUrl));
            preHooks = hooks.toArray(PreReceiveHook[]::new);
        } else {
            preHooks = validationHooks.toArray(PreReceiveHook[]::new);
        }

        rp.setPreReceiveHook(chainPreReceiveHooks(heartbeatInterval, preHooks));

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

    private static PreReceiveHook chainPreReceiveHooks(Duration heartbeatInterval, PreReceiveHook... hooks) {
        return (ReceivePack rp, Collection<ReceiveCommand> commands) -> {
            try (HeartbeatSender heartbeat = new HeartbeatSender(rp, heartbeatInterval)) {
                heartbeat.start();
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
