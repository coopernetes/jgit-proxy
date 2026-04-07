package org.finos.gitproxy.git;

import static org.finos.gitproxy.git.GitClientUtils.AnsiColor.*;
import static org.finos.gitproxy.git.GitClientUtils.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClientUtils.sym;

import java.util.Collection;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.finos.gitproxy.db.model.PushStep;
import org.finos.gitproxy.db.model.StepStatus;
import org.finos.gitproxy.permission.RepoPermissionService;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.service.PushIdentityResolver;
import org.finos.gitproxy.user.UserEntry;

/**
 * Pre-receive hook that validates the pushing user has permission to push to this repository. Mirrors the behaviour of
 * {@link org.finos.gitproxy.servlet.filter.CheckUserPushPermissionFilter} for store-and-forward mode.
 *
 * <p>Fail-closed: if no permission grants exist for the repository the push is denied. Skipped entirely when
 * {@link PushIdentityResolver} is {@code null} (open mode, no user store configured).
 *
 * <p>The push user is the authenticated HTTP Basic-auth username, stored in the repository config under
 * {@code gitproxy.pushUser} by {@link StoreAndForwardReceivePackFactory}. The repo slug is stored under
 * {@code gitproxy.repoSlug} by the same factory.
 */
@Slf4j
public class CheckUserPushPermissionHook implements GitProxyHook {

    private static final int ORDER = 150;

    private final PushIdentityResolver identityResolver;
    private final RepoPermissionService repoPermissionService;
    private final ValidationContext validationContext;
    private final PushContext pushContext;
    private final GitProxyProvider provider;
    private final String serviceUrl;

    public CheckUserPushPermissionHook(
            PushIdentityResolver identityResolver,
            RepoPermissionService repoPermissionService,
            ValidationContext validationContext,
            PushContext pushContext) {
        this(identityResolver, repoPermissionService, validationContext, pushContext, null, null);
    }

    public CheckUserPushPermissionHook(
            PushIdentityResolver identityResolver,
            RepoPermissionService repoPermissionService,
            ValidationContext validationContext,
            PushContext pushContext,
            GitProxyProvider provider,
            String serviceUrl) {
        this.identityResolver = identityResolver;
        this.repoPermissionService = repoPermissionService;
        this.validationContext = validationContext;
        this.pushContext = pushContext;
        this.provider = provider;
        this.serviceUrl = serviceUrl;
    }

    @Override
    public void onPreReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
        var config = rp.getRepository().getConfig();
        String pushUser = config.getString("gitproxy", null, "pushUser");
        String pushToken = config.getString("gitproxy", null, "pushToken");
        String repoSlug = config.getString("gitproxy", null, "repoSlug");

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
            log.debug("No push user found in repo config, skipping permission check");
            pushContext.addStep(PushStep.builder()
                    .stepName("checkUserPermission")
                    .stepOrder(ORDER)
                    .status(StepStatus.PASS)
                    .build());
            return;
        }

        Optional<UserEntry> resolved =
                identityResolver != null ? identityResolver.resolve(provider, pushUser, pushToken) : Optional.empty();

        if (resolved.isEmpty()) {
            log.warn("Push user '{}' could not be resolved to a registered proxy user", pushUser);
            String providerName = provider != null ? provider.getName() : "SCM";
            String profileHint = serviceUrl != null
                    ? "Link your " + providerName + " identity at:\n  " + sym(LINK) + "  " + serviceUrl + "/profile"
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

        UserEntry user = resolved.get();
        String providerName = provider != null ? provider.getName() : null;

        if (providerName == null
                || repoSlug == null
                || !repoPermissionService.isAllowedToPush(user.getUsername(), providerName, repoSlug)) {
            log.warn(
                    "Push user '{}' (resolved as '{}') is not authorized for {}/{}",
                    pushUser,
                    user.getUsername(),
                    providerName,
                    repoSlug);
            String repoRef = providerName != null && repoSlug != null
                    ? String.format("https://%s%s", providerName, repoSlug)
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

        log.debug(
                "Push user '{}' resolved as '{}' and authorized for {}/{}",
                pushUser,
                user.getUsername(),
                providerName,
                repoSlug);
        config.setString("gitproxy", null, "resolvedUser", user.getUsername());
        if (provider != null && user.getScmIdentities() != null) {
            user.getScmIdentities().stream()
                    .filter(id -> provider.getName().equalsIgnoreCase(id.getProvider()))
                    .map(org.finos.gitproxy.user.ScmIdentity::getUsername)
                    .findFirst()
                    .ifPresent(scmUser -> config.setString("gitproxy", null, "scmUsername", scmUser));
        }
        pushContext.addStep(PushStep.builder()
                .stepName("checkUserPermission")
                .stepOrder(ORDER)
                .status(StepStatus.PASS)
                .build());
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
