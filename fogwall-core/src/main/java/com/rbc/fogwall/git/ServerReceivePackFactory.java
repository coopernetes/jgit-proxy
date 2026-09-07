package com.rbc.fogwall.git;

import com.rbc.fogwall.approval.ApprovalGateway;
import com.rbc.fogwall.config.BinaryBlobConfig;
import com.rbc.fogwall.config.CommitConfig;
import com.rbc.fogwall.config.ContentPatternConfig;
import com.rbc.fogwall.config.DiffScanConfig;
import com.rbc.fogwall.config.GpgConfig;
import com.rbc.fogwall.config.ScmOAuthConfig;
import com.rbc.fogwall.config.SecretScanConfig;
import com.rbc.fogwall.db.PushStore;
import com.rbc.fogwall.db.UrlRuleRegistry;
import com.rbc.fogwall.permission.RepoPermissionService;
import com.rbc.fogwall.provider.BitbucketProvider;
import com.rbc.fogwall.provider.FogwallProvider;
import com.rbc.fogwall.service.PushIdentityResolver;
import com.rbc.fogwall.service.SshScmIdentityEnricher;
import com.rbc.fogwall.user.UserEntry;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;

/**
 * Factory that creates {@link ReceivePack} instances for server mode push handling. Extracts credentials from the HTTP
 * request's Basic auth header and wires up the pre/post receive hooks.
 *
 * <p>This factory creates new hook instances per request since each push has its own credentials.
 */
@Slf4j
public class ServerReceivePackFactory implements ReceivePackFactory<HttpServletRequest> {

    private static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(10);
    private static final Duration DEFAULT_APPROVAL_TIMEOUT = Duration.ofMinutes(30);

    private final FogwallProvider provider;
    private final Supplier<CommitConfig> commitConfigSupplier;
    private final Supplier<DiffScanConfig> diffScanConfigSupplier;
    private final Supplier<SecretScanConfig> secretScanConfigSupplier;
    private final Supplier<BinaryBlobConfig> binaryBlobConfigSupplier;
    private Supplier<ScmOAuthConfig> scmOAuthConfigSupplier = ScmOAuthConfig::defaultConfig;
    private final ContentPatternConfig contentPatternConfig;
    private final GpgConfig gpgConfig;
    private final RepoPermissionService repoPermissionService;
    private final PushIdentityResolver pushIdentityResolver;
    private SshScmIdentityEnricher sshScmIdentityEnricher;
    private final PushStore pushStore;
    private final ApprovalGateway approvalGateway;
    private final String serviceUrl;
    private final Duration heartbeatInterval;
    private final UrlRuleRegistry urlRuleRegistry;
    private LocalRepositoryCache cache;

    /** Stop the validation hook chain after the first failure (see {@link ServerConfig#isFailFast()}). */
    private boolean failFast = false;

    /** Connect timeout in seconds passed to the JGit {@link org.eclipse.jgit.transport.Transport} (0 = no timeout). */
    private int connectTimeoutSeconds = 0;

    /** Maximum time a push waits for human review before being marked timed out (see {@link ServerConfig}). */
    private Duration approvalTimeout = DEFAULT_APPROVAL_TIMEOUT;

    /**
     * Largest pack (wire bytes) the {@link ReceivePack} will accept; 0 = unlimited. On HTTP this duplicates the cap
     * {@code ParseGitRequestFilter} already enforces, but that filter never sees SSH pushes — setting it here is what
     * bounds the SSH transport at all.
     */
    private long maxPackBytes = 0;

    /** Largest decompressed size of any single received object; 0 = unlimited. Guards against decompression bombs. */
    private long maxObjectSizeBytes = 0;

    /** Enable fail-fast mode. Call after construction before the factory handles any requests. */
    public void setFailFast(boolean failFast) {
        this.failFast = failFast;
    }

    /** Set the upstream connect timeout. Call after construction before the factory handles any requests. */
    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    /** Set the wire-size pack limit ({@code server.max-push-bytes}). Call before the factory handles any requests. */
    public void setMaxPackBytes(long maxPackBytes) {
        this.maxPackBytes = maxPackBytes;
    }

    /** Set the per-object decompressed size limit ({@code server.max-object-size-bytes}). */
    public void setMaxObjectSizeBytes(long maxObjectSizeBytes) {
        this.maxObjectSizeBytes = maxObjectSizeBytes;
    }

    /** Set the local repository cache for invalidation on forward failure. */
    public void setCache(LocalRepositoryCache cache) {
        this.cache = cache;
    }

    public void setSshScmIdentityEnricher(SshScmIdentityEnricher enricher) {
        this.sshScmIdentityEnricher = enricher;
    }

    /** Set the live SCM OAuth config supplier (#40 — {@code scm-oauth.identity-mode}). Defaults to permissive. */
    public void setScmOAuthConfigSupplier(Supplier<ScmOAuthConfig> scmOAuthConfigSupplier) {
        this.scmOAuthConfigSupplier =
                scmOAuthConfigSupplier != null ? scmOAuthConfigSupplier : ScmOAuthConfig::defaultConfig;
    }

    /** Set the approval-wait timeout. Call after construction before the factory handles any requests. */
    public void setApprovalTimeout(Duration approvalTimeout) {
        this.approvalTimeout = approvalTimeout != null ? approvalTimeout : DEFAULT_APPROVAL_TIMEOUT;
    }

    /** Fixed-config constructors for use in tests and simple setups (no URL rule enforcement). */
    public ServerReceivePackFactory(
            FogwallProvider provider, CommitConfig commitConfig, PushStore pushStore, ApprovalGateway approvalGateway) {
        this(
                provider,
                () -> commitConfig,
                DiffScanConfig::defaultConfig,
                SecretScanConfig::defaultConfig,
                BinaryBlobConfig::defaultConfig,
                ContentPatternConfig.defaultConfig(),
                GpgConfig.defaultConfig(),
                null,
                null,
                pushStore,
                approvalGateway,
                null,
                DEFAULT_HEARTBEAT_INTERVAL,
                null);
    }

    public ServerReceivePackFactory(
            FogwallProvider provider,
            CommitConfig commitConfig,
            GpgConfig gpgConfig,
            RepoPermissionService repoPermissionService,
            PushIdentityResolver pushIdentityResolver,
            PushStore pushStore,
            ApprovalGateway approvalGateway,
            String serviceUrl,
            Duration heartbeatInterval) {
        this(
                provider,
                () -> commitConfig,
                DiffScanConfig::defaultConfig,
                SecretScanConfig::defaultConfig,
                BinaryBlobConfig::defaultConfig,
                ContentPatternConfig.defaultConfig(),
                gpgConfig,
                repoPermissionService,
                pushIdentityResolver,
                pushStore,
                approvalGateway,
                serviceUrl,
                heartbeatInterval,
                null);
    }

    public ServerReceivePackFactory(
            FogwallProvider provider,
            Supplier<CommitConfig> commitConfigSupplier,
            Supplier<DiffScanConfig> diffScanConfigSupplier,
            Supplier<SecretScanConfig> secretScanConfigSupplier,
            Supplier<BinaryBlobConfig> binaryBlobConfigSupplier,
            ContentPatternConfig contentPatternConfig,
            GpgConfig gpgConfig,
            RepoPermissionService repoPermissionService,
            PushIdentityResolver pushIdentityResolver,
            PushStore pushStore,
            ApprovalGateway approvalGateway,
            String serviceUrl,
            Duration heartbeatInterval,
            UrlRuleRegistry urlRuleRegistry) {
        this.provider = provider;
        this.commitConfigSupplier = commitConfigSupplier;
        this.diffScanConfigSupplier =
                diffScanConfigSupplier != null ? diffScanConfigSupplier : DiffScanConfig::defaultConfig;
        this.secretScanConfigSupplier =
                secretScanConfigSupplier != null ? secretScanConfigSupplier : SecretScanConfig::defaultConfig;
        this.binaryBlobConfigSupplier =
                binaryBlobConfigSupplier != null ? binaryBlobConfigSupplier : BinaryBlobConfig::defaultConfig;
        this.contentPatternConfig =
                contentPatternConfig != null ? contentPatternConfig : ContentPatternConfig.defaultConfig();
        this.gpgConfig = gpgConfig != null ? gpgConfig : GpgConfig.defaultConfig();
        this.repoPermissionService = repoPermissionService;
        this.pushIdentityResolver = pushIdentityResolver;
        // The push store and approval gateway are security controls, not optional collaborators: without the
        // store there is no push record (no audit trail, no approval state), and without the gateway nothing
        // gates forwarding. Failing here beats assembling a hook chain that silently skips both.
        this.pushStore = Objects.requireNonNull(
                pushStore,
                "pushStore is required: without it pushes would forward with no record and no approval gate");
        this.approvalGateway =
                Objects.requireNonNull(approvalGateway, "approvalGateway is required: nothing else gates forwarding");
        this.serviceUrl = serviceUrl;
        this.heartbeatInterval = heartbeatInterval != null ? heartbeatInterval : DEFAULT_HEARTBEAT_INTERVAL;
        this.urlRuleRegistry = urlRuleRegistry;
    }

    @Override
    public ReceivePack create(HttpServletRequest req, Repository db)
            throws ServiceNotEnabledException, ServiceNotAuthorizedException {

        CredentialsProvider creds =
                (CredentialsProvider) req.getAttribute(ServerRepositoryResolver.CREDENTIALS_ATTRIBUTE);
        if (creds == null) {
            creds = extractBasicAuth(req);
        }

        String[] userPass = extractUserPass(req);
        String pushUser = userPass != null ? userPass[0] : null;
        String pushToken = userPass != null ? userPass[1] : null;

        // Null when the path does not name a repository; RepositoryUrlRuleHook blocks the push fail-closed rather
        // than evaluating rules against a partial slug.
        String repoSlug = RepoPath.parse(req.getPathInfo()).map(RepoPath::slug).orElse(null);

        String upstreamUrl = (String) req.getAttribute(ServerRepositoryResolver.UPSTREAM_URL_ATTRIBUTE);

        // Mint the push record's id here rather than in PushStorePersistenceHook, so the quarantine directory
        // on disk carries the same id an operator will see in the audit record.
        String pushId = UUID.randomUUID().toString();

        // Receive into a scratch store so a rejected push leaves nothing behind in the shared mirror.
        // QuarantineCleanupFilter discards it when the request ends.
        Repository target = db;
        QuarantineObjectStore quarantine = QuarantineObjectStore.createOrNull(db, pushId);
        if (quarantine != null) {
            req.setAttribute(QuarantineObjectStore.REQUEST_ATTRIBUTE, quarantine);
            target = quarantine.getRepository();
        }

        return buildReceivePack(
                target, creds, pushUser, pushToken, repoSlug, upstreamUrl, PushTransport.http(), quarantine, pushId);
    }

    /**
     * Builds a {@link ReceivePack} for an SSH push. The {@code transport} carries the {@link UserEntry} identified
     * during public-key authentication and the per-push SSH session factory for upstream forwarding.
     */
    public ReceivePack createForSsh(
            Repository db,
            String pushUser,
            String repoSlug,
            String upstreamUrl,
            PushTransport.Ssh transport,
            QuarantineObjectStore quarantine,
            String pushId)
            throws ServiceNotEnabledException, ServiceNotAuthorizedException {
        return buildReceivePack(db, null, pushUser, null, repoSlug, upstreamUrl, transport, quarantine, pushId);
    }

    private ReceivePack buildReceivePack(
            Repository db,
            CredentialsProvider creds,
            String pushUser,
            String pushToken,
            String repoSlug,
            String upstreamUrl,
            PushTransport transport,
            QuarantineObjectStore quarantine,
            String pushId)
            throws ServiceNotEnabledException, ServiceNotAuthorizedException {

        ReceivePack rp = new ReceivePack(db);
        rp.setBiDirectionalPipe(false);
        // Never advertise push-options: git push -o values are upstream commands (merge-request creation,
        // CI skips, visibility changes) that no hook inspects, so a client that sends them must fail at
        // negotiation. This is JGit's default, stated here so a library upgrade cannot change the posture.
        rp.setAllowPushOptions(false);

        // Bound what JGit will accept before any hook runs: the pack limit caps wire bytes (the only such cap on
        // the SSH transport), and the object limit caps what those bytes may inflate to.
        if (maxPackBytes > 0) {
            rp.setMaxPackSizeLimit(maxPackBytes);
        }
        if (maxObjectSizeBytes > 0) {
            rp.setMaxObjectSizeLimit(maxObjectSizeBytes);
        }

        // Per-request shared contexts
        var validationContext = new ValidationContext();
        var pushContext = new PushContext();

        pushContext.setPushUser(pushUser);
        pushContext.setPushToken(pushToken);
        pushContext.setPushId(pushId);
        // Fix the receipt time at this handoff (transport request deserialized → fogwall business logic), so the
        // lifecycle record's receivedAt reflects when the push arrived, not when the record is first persisted.
        pushContext.setReceivedAt(Instant.now());
        pushContext.setRepoSlug(repoSlug);
        pushContext.setUpstreamUrl(upstreamUrl);
        pushContext.setTransport(transport);
        // Expose the quarantine's object directory so SecretScanningHook can hand it to gitleaks as a git alternate;
        // gitleaks shells out to git against the mirror and would otherwise never see the quarantined push objects.
        if (quarantine != null) {
            pushContext.setScanObjectDirectory(quarantine.getObjectsDirectory());
        }

        // Persistence hook (records push to database). The store and gateway are constructor-required, so the
        // persistence and approval hooks are always in the chain — no wiring can assemble a push path without them.
        var persistenceHook = new PushStorePersistenceHook(pushStore, provider);
        persistenceHook.setPushContext(pushContext);
        persistenceHook.setServiceUrl(serviceUrl);
        persistenceHook.setAutoApproval(approvalGateway.approvesImmediately());

        // Orderable validation hooks - sorted by getOrder() before chaining.
        // Lifecycle hooks (persistence, approval) are pinned outside this list.
        //
        // Authorization range (0-199):
        //   RepositoryUrlRuleHook           (100) - URL rule PASS (resolver already validated)
        //   CheckUserPushPermissionHook     (150) - push user authorization
        // Content filtering range (200-399):
        //   CheckEmptyBranchHook            (210) - reject if no commits introduced (short-circuit)
        //   CheckHiddenCommitsHook          (220) - reject if pack contains commits outside push range
        //   AuthorEmailValidationHook       (250) - validates emails
        //   TrailerPolicyValidationHook     (255) - DCO Signed-off-by / Co-authored-by policy
        //   CommitMessageValidationHook     (260) - validates messages
        //   ContentPatternCommitMessageHook (265) - WARN-only PII/identifier scan of commit messages
        //   ProxyPreReceiveHook             (270) - commit inspection
        //   DiffGenerationHook              (280) - generates diffs for scanning and persistence
        //   DiffScanningHook                (300) - scans diff added-lines for blocked content
        //   GpgSignatureHook                (320) - checks GPG signatures
        //   SecretScanningHook              (340) - pipes diff to gitleaks
        //   ContentPatternDiffHook          (345) - WARN-only PII/identifier scan of the diff
        //
        // Pinned lifecycle hooks (not orderable):
        //   [post-validation] PushStorePersistenceHook.validationResult - creates the single record (PENDING/REJECTED)
        //   [post-validation] ApprovalPreReceiveHook        - blocks until approved or timeout
        //   [last]  QuarantinePromotionHook                  - moves objects into the mirror once nothing rejected
        //
        // Post-receive:
        //   ForwardingPostReceiveHook       - forwards to upstream
        //   PushStorePersistenceHook.postReceive - transitions that same record to FORWARDED/ERROR

        // Snapshot current config for this push — all hooks in one push see the same config even if a reload fires
        // mid-push.
        CommitConfig commitConfig = commitConfigSupplier.get();
        DiffScanConfig diffScanConfig = diffScanConfigSupplier.get();
        SecretScanConfig secretScanConfig = secretScanConfigSupplier.get();
        BinaryBlobConfig binaryBlobConfig = binaryBlobConfigSupplier.get();
        ScmOAuthConfig scmOAuthConfig = scmOAuthConfigSupplier.get();

        var permissionHook = new CheckUserPushPermissionHook(
                pushIdentityResolver,
                repoPermissionService,
                validationContext,
                pushContext,
                provider,
                serviceUrl,
                sshScmIdentityEnricher,
                scmOAuthConfig.getIdentityMode());

        var attributionPolicyHook = new CommitAttributionPolicyHook(
                pushIdentityResolver, commitConfig.getAttributionPolicy(), validationContext, pushContext, provider);

        // Build and sort the orderable validation hook list
        List<FogwallHook> validationHooks = new ArrayList<>(List.of(
                new RepositoryUrlRuleHook(urlRuleRegistry, provider, validationContext, pushContext),
                permissionHook,
                attributionPolicyHook,
                new CheckEmptyBranchHook(pushContext),
                new CheckHiddenCommitsHook(pushContext),
                new AuthorEmailValidationHook(commitConfig, validationContext, pushContext),
                new TrailerPolicyValidationHook(commitConfig, validationContext, pushContext),
                new CommitMessageValidationHook(commitConfig, validationContext, pushContext),
                new ContentPatternCommitMessageHook(contentPatternConfig, pushContext),
                new ProxyPreReceiveHook(pushContext),
                new DiffGenerationHook(validationContext, pushContext),
                new BinaryBlobDetectionHook(binaryBlobConfig, validationContext, pushContext),
                new DiffScanningHook(diffScanConfig, validationContext, pushContext),
                new GpgSignatureHook(gpgConfig, validationContext, pushContext),
                new SecretScanningHook(secretScanConfig, validationContext, pushContext),
                new ContentPatternDiffHook(contentPatternConfig, pushContext)));
        if (provider instanceof BitbucketProvider bitbucketProvider) {
            validationHooks.add(new BitbucketCredentialRewriteHook(bitbucketProvider, pushContext));
        }
        validationHooks.add(new PriorPushEnrichmentHook(pushStore, pushContext));
        validationHooks.sort(Comparator.comparingInt(FogwallHook::getOrder));

        List<PreReceiveHook> hooks = new ArrayList<>(validationHooks);
        hooks.add(persistenceHook.validationResultHook(validationContext));
        hooks.add(new ApprovalPreReceiveHook(
                pushStore, approvalGateway, approvalTimeout, serviceUrl, repoPermissionService, pushContext));
        if (quarantine != null) hooks.add(new QuarantinePromotionHook(quarantine));
        PreReceiveHook[] preHooks = hooks.toArray(PreReceiveHook[]::new);

        final PushContext capturedContext = pushContext;
        Runnable disconnectCallback = () -> {
            // Only a submission that reached PENDING has a persisted lifecycle record to cancel. A disconnect
            // before that — during the synchronous receive/validation phase — has no record yet, and the spec
            // state machine has no received->canceled transition, so there is nothing to record.
            String recordId = capturedContext.getValidationRecordId();
            if (recordId != null) {
                try {
                    pushStore.cancel(recordId, null);
                    log.info("Push {} marked CANCELED: client disconnected mid-push", recordId);
                } catch (Exception e) {
                    log.warn("Failed to mark push {} as CANCELED after client disconnect", recordId, e);
                }
            }
        };

        rp.setPreReceiveHook(
                chainPreReceiveHooks(heartbeatInterval, validationContext, failFast, disconnectCallback, preHooks));

        // Post-receive: forward to upstream, then record final status
        var forwardingHook = new ForwardingPostReceiveHook(creds, pushContext, connectTimeoutSeconds, cache);
        rp.setPostReceiveHook(chainPostReceiveHooks(forwardingHook, persistenceHook.postReceiveHook()));

        log.debug("Created ReceivePack for {} with {} auth", provider.getName(), creds != null ? "credentials" : "no");

        return rp;
    }

    private static PreReceiveHook chainPreReceiveHooks(
            Duration heartbeatInterval,
            ValidationContext validationContext,
            boolean failFast,
            Runnable disconnectCallback,
            PreReceiveHook... hooks) {
        return (ReceivePack rp, Collection<ReceiveCommand> commands) -> {
            try (HeartbeatSender heartbeat = new HeartbeatSender(rp, heartbeatInterval, disconnectCallback)) {
                heartbeat.start();
                boolean skipValidationHooks = false;
                for (PreReceiveHook hook : hooks) {
                    // Fail-fast: skip remaining fogwallHook (validation) hooks after first issue.
                    // Lifecycle hooks (persistence, approval) do not implement fogwallHook and always run.
                    if (skipValidationHooks && hook instanceof FogwallHook) {
                        continue;
                    }
                    // Pause heartbeat dots while the approval hook streams its own progress messages to
                    // the client; dots interleave with gateway messages without this guard.
                    boolean isApprovalHook = hook instanceof ApprovalPreReceiveHook;
                    if (isApprovalHook) {
                        heartbeat.pause();
                    }
                    try {
                        hook.onPreReceive(rp, commands);
                    } finally {
                        if (isApprovalHook) {
                            heartbeat.resume();
                        }
                    }
                    // Flush sideband after each hook so messages stream to the client in real time
                    // (JGit's sendMessage() doesn't flush - without this, all output batches up)
                    try {
                        rp.getMessageOutputStream().flush();
                    } catch (IOException e) {
                        log.warn("Failed to flush sideband stream", e);
                    }
                    // Stop chain if any command was rejected (e.g. by a lifecycle hook)
                    if (commands.stream().anyMatch(cmd -> cmd.getResult() != ReceiveCommand.Result.NOT_ATTEMPTED)) {
                        return;
                    }
                    // After a validation hook reports an issue, mark remaining validation hooks to skip
                    if (failFast && hook instanceof FogwallHook && validationContext.hasIssues()) {
                        skipValidationHooks = true;
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
            String decoded = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
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
