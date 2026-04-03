package org.finos.gitproxy.git;

import static org.finos.gitproxy.git.GitClient.AnsiColor.*;
import static org.finos.gitproxy.git.GitClient.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClient.color;
import static org.finos.gitproxy.git.GitClient.sym;

import java.util.Collection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.finos.gitproxy.db.model.PushStep;
import org.finos.gitproxy.db.model.StepStatus;
import org.finos.gitproxy.service.UserAuthorizationService;

/**
 * Pre-receive hook that validates the pushing user has permission to push to this repository. Mirrors the behavior of
 * {@link org.finos.gitproxy.servlet.filter.CheckUserPushPermissionFilter} for store-and-forward mode.
 *
 * <p>The push user is the authenticated HTTP Basic-auth username, stored in the repository config under
 * {@code gitproxy.pushUser} by {@link StoreAndForwardReceivePackFactory}.
 */
@Slf4j
@RequiredArgsConstructor
public class CheckUserPushPermissionHook implements PreReceiveHook {

    private static final int STEP_ORDER = 2000;

    private final UserAuthorizationService userAuthorizationService;
    private final ValidationContext validationContext;
    private final PushContext pushContext;

    @Override
    public void onPreReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
        String pushUser = rp.getRepository().getConfig().getString("gitproxy", null, "pushUser");

        if (pushUser == null || pushUser.isEmpty()) {
            log.debug("No push user found in repo config, skipping permission check");
            pushContext.addStep(PushStep.builder()
                    .stepName("checkUserPermission")
                    .stepOrder(STEP_ORDER)
                    .status(StepStatus.PASS)
                    .build());
            return;
        }

        if (!userAuthorizationService.userExists(pushUser)) {
            log.warn("Push user {} does not exist", pushUser);
            String detail = GitClient.format(
                    sym(NO_ENTRY) + "  Push Blocked — User Not Registered",
                    sym(CROSS_MARK) + "  " + pushUser + " is not registered.\n\nContact an administrator for support.",
                    RED,
                    null);
            validationContext.addIssue("CheckUserPushPermissionHook", "User does not exist: " + pushUser, detail);
            rp.sendMessage(color(RED, "  " + sym(CROSS_MARK) + "  " + pushUser + " — not registered"));
            return;
        }

        if (!userAuthorizationService.isUserAuthorizedToPush(pushUser, null)) {
            log.warn("Push user {} is not authorized", pushUser);
            String detail = GitClient.format(
                    sym(NO_ENTRY) + "  Push Blocked — Unauthorized",
                    sym(CROSS_MARK) + "  " + pushUser + " is not authorized to push to this repository.",
                    RED,
                    null);
            validationContext.addIssue("CheckUserPushPermissionHook", "User not authorized: " + pushUser, detail);
            rp.sendMessage(color(RED, "  " + sym(CROSS_MARK) + "  " + pushUser + " — not authorized"));
            return;
        }

        log.debug("Push user {} is authorized", pushUser);
        pushContext.addStep(PushStep.builder()
                .stepName("checkUserPermission")
                .stepOrder(STEP_ORDER)
                .status(StepStatus.PASS)
                .build());
    }
}
