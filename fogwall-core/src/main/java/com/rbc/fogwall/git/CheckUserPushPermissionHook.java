package com.rbc.fogwall.git;

import static com.rbc.fogwall.git.GitClientUtils.AnsiColor.*;
import static com.rbc.fogwall.git.GitClientUtils.SymbolCodes.*;
import static com.rbc.fogwall.git.GitClientUtils.sym;

import com.rbc.fogwall.config.ScmOAuthConfig;
import com.rbc.fogwall.db.model.PushStep;
import com.rbc.fogwall.db.model.StepStatus;
import com.rbc.fogwall.permission.RepoPermissionService;
import com.rbc.fogwall.provider.FogwallProvider;
import com.rbc.fogwall.provider.SshKeyFingerprintLookup;
import com.rbc.fogwall.service.PushIdentityResolver;
import com.rbc.fogwall.service.SshScmLoginResolver;
import com.rbc.fogwall.servlet.filter.CheckUserPushPermissionFilter;
import com.rbc.fogwall.user.ScmIdentity;
import com.rbc.fogwall.user.UserEntry;
import java.util.Collection;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;

/**
 * Pre-receive hook that validates the pushing user has permission to push to this repository. Mirrors the behaviour of
 * {@link CheckUserPushPermissionFilter} for server mode.
 *
 * <p>Fail-closed: if no permission grants exist for the repository the push is denied. Skipped entirely when
 * {@link PushIdentityResolver} is {@code null} (open mode, no user store configured).
 *
 * <p>The push user is the authenticated HTTP Basic-auth username, stored in the repository config under
 * {@code fogwall.pushUser} by {@link ServerReceivePackFactory}. The repo slug is stored under {@code fogwall.repoSlug}
 * by the same factory.
 */
@Slf4j
public class CheckUserPushPermissionHook implements FogwallHook {

    private static final int ORDER = 150;

    private final PushIdentityResolver identityResolver;
    private final RepoPermissionService repoPermissionService;
    private final ValidationContext validationContext;
    private final PushContext pushContext;
    private final FogwallProvider provider;
    private final String serviceUrl;
    private final SshScmLoginResolver sshLoginResolver;
    private final ScmOAuthConfig.IdentityMode identityMode;

    public CheckUserPushPermissionHook(
            PushIdentityResolver identityResolver,
            RepoPermissionService repoPermissionService,
            ValidationContext validationContext,
            PushContext pushContext) {
        this(identityResolver, repoPermissionService, validationContext, pushContext, null, null, null);
    }

    public CheckUserPushPermissionHook(
            PushIdentityResolver identityResolver,
            RepoPermissionService repoPermissionService,
            ValidationContext validationContext,
            PushContext pushContext,
            FogwallProvider provider,
            String serviceUrl) {
        this(identityResolver, repoPermissionService, validationContext, pushContext, provider, serviceUrl, null);
    }

    public CheckUserPushPermissionHook(
            PushIdentityResolver identityResolver,
            RepoPermissionService repoPermissionService,
            ValidationContext validationContext,
            PushContext pushContext,
            FogwallProvider provider,
            String serviceUrl,
            SshScmLoginResolver sshLoginResolver) {
        this(
                identityResolver,
                repoPermissionService,
                validationContext,
                pushContext,
                provider,
                serviceUrl,
                sshLoginResolver,
                ScmOAuthConfig.IdentityMode.PERMISSIVE);
    }

    /**
     * Full constructor, additionally taking the {@code scm-oauth.identity-mode} (#40). In {@code STRICT} mode, only
     * OAuth-verified SCM identities count for push authorization — on both HTTP and SSH.
     */
    public CheckUserPushPermissionHook(
            PushIdentityResolver identityResolver,
            RepoPermissionService repoPermissionService,
            ValidationContext validationContext,
            PushContext pushContext,
            FogwallProvider provider,
            String serviceUrl,
            SshScmLoginResolver sshLoginResolver,
            ScmOAuthConfig.IdentityMode identityMode) {
        this.identityResolver = identityResolver;
        this.repoPermissionService = repoPermissionService;
        this.validationContext = validationContext;
        this.pushContext = pushContext;
        this.provider = provider;
        this.serviceUrl = serviceUrl;
        this.sshLoginResolver = sshLoginResolver;
        this.identityMode = identityMode != null ? identityMode : ScmOAuthConfig.IdentityMode.PERMISSIVE;
    }

    @Override
    public void onPreReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
        String pushUser = pushContext.getPushUser();
        String pushToken = pushContext.getPushToken();
        String repoSlug = pushContext.getRepoSlug();

        // SSH transport: user resolved by public-key auth — skip token-based identity resolution.
        var preAuthenticated = pushContext.getTransport().preAuthenticatedUser();
        if (preAuthenticated.isPresent()) {
            checkRepoPermission(preAuthenticated.get(), repoSlug);
            return;
        }

        if (identityResolver == null) {
            log.debug("No identity resolver configured (open mode), skipping permission check");
            pushContext.addStep(PushStep.builder()
                    .stepName("checkUserPermission")
                    .stepOrder(ORDER)
                    .status(StepStatus.PASS)
                    .build());
            return;
        }

        if (pushUser == null || pushUser.isEmpty()) {
            log.warn("No authenticated push user in repo config — push denied (fail-closed)");
            validationContext.addIssue(
                    "CheckUserPushPermissionHook", "No authenticated user", "Push rejected: no authenticated user.");
            return;
        }

        Optional<UserEntry> resolved =
                identityResolver != null ? identityResolver.resolve(provider, pushUser, pushToken) : Optional.empty();

        if (resolved.isEmpty()) {
            log.warn("Push user '{}' could not be resolved to a registered proxy user", pushUser);
            String providerName = provider != null ? provider.getName() : "SCM";
            String profileHint = serviceUrl != null
                    ? "Link your " + providerName + " identity at:\n  " + sym(LINK) + "  " + serviceUrl
                            + "/dashboard/profile"
                    : "Ask an administrator to link your " + providerName + " identity to your proxy account.";
            String detail = GitClientUtils.format(
                    sym(NO_ENTRY) + "  Push Blocked - Identity Not Linked",
                    sym(CROSS_MARK)
                            + "  Your "
                            + providerName
                            + " credentials could not be matched to a proxy account.\n\n"
                            + profileHint,
                    RED,
                    null);
            validationContext.addIssue("CheckUserPushPermissionHook", "Identity not linked: " + pushUser, detail);
            return;
        }

        checkRepoPermission(resolved.get(), repoSlug);
    }

    private void checkRepoPermission(UserEntry user, String repoSlug) {
        String providerId = provider != null ? provider.getProviderId() : null;

        if (providerId == null
                || repoSlug == null
                || !repoPermissionService.isAllowedToPush(user.getUsername(), providerId, repoSlug)) {
            log.warn("User '{}' is not authorized for {}/{}", user.getUsername(), providerId, repoSlug);
            String repoRef = provider != null && repoSlug != null
                    ? provider.getUri().toString().replaceAll("/$", "") + repoSlug
                    : repoSlug;
            String detail = GitClientUtils.format(
                    sym(NO_ENTRY) + "  Push Blocked - Unauthorized",
                    sym(CROSS_MARK) + "  " + user.getUsername() + " is not allowed to push to:\n   " + sym(LINK) + "  "
                            + repoRef,
                    RED,
                    null);
            validationContext.addIssue(
                    "CheckUserPushPermissionHook", "User not authorized: " + user.getUsername(), detail);
            return;
        }

        log.debug("User '{}' authorized for {}/{}", user.getUsername(), providerId, repoSlug);
        pushContext.setResolvedUser(user.getUsername());

        if (pushContext.getTransport() instanceof PushTransport.Ssh sshTransport) {
            // SSH: fingerprint-based SCM identity verification is required — same compliance guarantee as HTTP token
            // verification. Fail closed in all cases where we cannot confirm the connecting key belongs to a linked
            // SCM account.
            if (identityMode == ScmOAuthConfig.IdentityMode.STRICT) {
                // Strict mode answers from what OAuth linking proved and nothing else, so the provider is not called
                // and need not support key listing at all. Re-reading a public key endpoint would only re-prove, more
                // weakly, what an authenticated OAuth session already established.
                Optional<String> importedLogin =
                        sshLoginResolver.resolveScmLogin(user, provider, sshTransport.connectingFingerprint());
                if (importedLogin.isEmpty()) {
                    blockUnimportedSshKey(user, providerId);
                    return;
                }
                pushContext.setScmUsername(importedLogin.get());
                pushContext.addStep(PushStep.builder()
                        .stepName("checkUserPermission")
                        .stepOrder(ORDER)
                        .status(StepStatus.PASS)
                        .build());
                return;
            }
            if (!(provider instanceof SshKeyFingerprintLookup)) {
                log.warn(
                        "Provider '{}' does not support SSH fingerprint lookup — SSH push denied (fail-closed)",
                        provider != null ? provider.getName() : "null");
                String detail = GitClientUtils.format(
                        sym(NO_ENTRY) + "  Push Blocked - SSH Identity Verification Unavailable",
                        sym(CROSS_MARK) + "  Provider '" + (provider != null ? provider.getName() : "unknown")
                                + "' does not support SSH key fingerprint lookup.\n"
                                + "  Use HTTP/HTTPS transport to push to this provider.",
                        RED,
                        null);
                validationContext.addIssue(
                        "CheckUserPushPermissionHook", "SSH identity verification not supported by provider", detail);
                return;
            }
            Optional<String> scmLogin = sshLoginResolver != null && sshTransport.connectingFingerprint() != null
                    ? sshLoginResolver.resolveScmLogin(user, provider, sshTransport.connectingFingerprint())
                    : Optional.empty();
            if (scmLogin.isEmpty()) {
                log.warn(
                        "SSH fingerprint for user '{}' does not match any linked SCM identity on provider '{}'",
                        user.getUsername(),
                        providerId);
                String detail = GitClientUtils.format(
                        sym(NO_ENTRY) + "  Push Blocked - SSH Identity Not Verified",
                        sym(CROSS_MARK) + "  Your connecting SSH key is not registered on the SCM for any"
                                + " identity linked to your proxy account.\n"
                                + "  Register this key on your SCM profile and link it to your proxy account.",
                        RED,
                        null);
                validationContext.addIssue(
                        "CheckUserPushPermissionHook",
                        "SSH key not linked to any SCM identity for " + user.getUsername(),
                        detail);
                return;
            }
            pushContext.setScmUsername(scmLogin.get());
        } else if (provider != null) {
            // HTTP: scmUsername comes from the token lookup already performed during identity resolution
            var httpScmIdentities = user.getScmIdentities().stream()
                    .filter(id -> provider.getProviderId().equalsIgnoreCase(id.getProvider()));
            if (identityMode == ScmOAuthConfig.IdentityMode.STRICT) {
                httpScmIdentities = httpScmIdentities.filter(ScmIdentity::isVerified);
            }
            Optional<String> scmUsername =
                    httpScmIdentities.map(ScmIdentity::getUsername).findFirst();
            if (scmUsername.isEmpty() && identityMode == ScmOAuthConfig.IdentityMode.STRICT) {
                blockUnverifiedIdentity(user);
                return;
            }
            scmUsername.ifPresent(pushContext::setScmUsername);
        }
        pushContext.addStep(PushStep.builder()
                .stepName("checkUserPermission")
                .stepOrder(ORDER)
                .status(StepStatus.PASS)
                .build());
    }

    /** Blocks the push in {@code scm-oauth.identity-mode: strict} when no OAuth-verified SCM identity is usable. */
    private void blockUnverifiedIdentity(UserEntry user) {
        log.warn(
                "User '{}' has no OAuth-verified SCM identity — push denied (strict identity mode)",
                user.getUsername());
        String profileHint = serviceUrl != null
                ? "Link your account via OAuth at:\n  " + sym(LINK) + "  " + serviceUrl + "/dashboard/profile"
                : "Ask an administrator to link your SCM account via OAuth.";
        String detail = GitClientUtils.format(
                sym(NO_ENTRY) + "  Push Blocked - SCM Identity Not Verified",
                sym(CROSS_MARK) + "  Your SCM identity is not verified.\n\n" + profileHint,
                RED,
                null);
        validationContext.addIssue(
                "CheckUserPushPermissionHook", "No OAuth-verified SCM identity for " + user.getUsername(), detail);
    }

    /**
     * Blocks an SSH push in strict mode when the connecting key is not one OAuth linking imported for this provider.
     * Distinct from {@link #blockUnverifiedIdentity}: the identity may well be verified, and the key may well be
     * registered upstream — what is missing is that <em>this key</em> came from the linked account.
     */
    private void blockUnimportedSshKey(UserEntry user, String providerId) {
        // Names what was actually on file: the usual cause is a key added by hand, but "imported before the account
        // was linked" and "verified identity missing" look identical to a user without this.
        log.warn(
                "SSH key for user '{}' was not imported from provider '{}' by OAuth linking — push denied (strict"
                        + " identity mode). Keys on file: {}. Identities: {}",
                user.getUsername(),
                providerId,
                user.getSshKeys().stream()
                        .map(k ->
                                k.getFingerprint() + "[locked=" + k.isLocked() + ",sources=" + k.getAuthSources() + "]")
                        .toList(),
                user.getScmIdentities().stream()
                        .map(i -> i.getProvider() + "/" + i.getUsername() + "[verified=" + i.isVerified() + "]")
                        .toList());
        String profileHint = serviceUrl != null
                ? "Link your account at:\n  " + sym(LINK) + "  " + serviceUrl + "/dashboard/profile"
                : "Ask an administrator how to link your SCM account.";
        String detail = GitClientUtils.format(
                sym(NO_ENTRY) + "  Push Blocked - SSH Key Not Linked",
                sym(CROSS_MARK) + "  This SSH key is not linked to a verified account.\n\n" + profileHint,
                RED,
                null);
        validationContext.addIssue(
                "CheckUserPushPermissionHook",
                "SSH key not imported from " + providerId + " for " + user.getUsername(),
                detail);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public String getName() {
        return "CheckUserPushPermissionHook";
    }
}
