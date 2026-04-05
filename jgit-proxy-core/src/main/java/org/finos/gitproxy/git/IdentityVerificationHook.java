package org.finos.gitproxy.git;

import static org.finos.gitproxy.git.GitClientUtils.AnsiColor.*;
import static org.finos.gitproxy.git.GitClientUtils.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClientUtils.sym;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.db.model.PushStep;
import org.finos.gitproxy.db.model.StepStatus;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.service.PushIdentityResolver;
import org.finos.gitproxy.user.UserEntry;

/**
 * Pre-receive hook that verifies commit author and committer emails match a registered email of the authenticated push
 * user. Runs in store-and-forward mode at order 160 — after {@link CheckUserPushPermissionHook} (150) has confirmed the
 * user exists and is authorised, but before content-validation hooks (200+).
 *
 * <p>Behaviour is controlled by {@link CommitConfig#getIdentityVerification()}:
 *
 * <ul>
 *   <li>{@code STRICT} — blocks the push and reports all mismatching commits.
 *   <li>{@code WARN} — sends yellow sideband warnings but allows the push through (default).
 *   <li>{@code OFF} — skips the check entirely.
 * </ul>
 *
 * <p>When no {@link PushIdentityResolver} is configured (open/permissive mode) the hook is a no-op.
 */
@Slf4j
public class IdentityVerificationHook implements GitProxyHook {

    static final int ORDER = 160;
    static final String STEP_NAME = "identityVerification";

    private final PushIdentityResolver identityResolver;
    private final CommitConfig.IdentityVerificationMode mode;
    private final ValidationContext validationContext;
    private final PushContext pushContext;
    private final GitProxyProvider provider;

    public IdentityVerificationHook(
            PushIdentityResolver identityResolver,
            CommitConfig.IdentityVerificationMode mode,
            ValidationContext validationContext,
            PushContext pushContext,
            GitProxyProvider provider) {
        this.identityResolver = identityResolver;
        this.mode = mode != null ? mode : CommitConfig.IdentityVerificationMode.WARN;
        this.validationContext = validationContext;
        this.pushContext = pushContext;
        this.provider = provider;
    }

    @Override
    public void onPreReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
        if (mode == CommitConfig.IdentityVerificationMode.OFF) {
            log.debug("Identity verification disabled (mode=off)");
            recordPass();
            return;
        }

        if (identityResolver == null) {
            log.debug("No identity resolver configured — skipping identity verification (open mode)");
            recordPass();
            return;
        }

        var config = rp.getRepository().getConfig();
        String pushUser = config.getString("gitproxy", null, "pushUser");
        String pushToken = config.getString("gitproxy", null, "pushToken");

        if (pushUser == null || pushUser.isEmpty()) {
            log.debug("No push user in repo config — skipping identity verification");
            recordPass();
            return;
        }

        Optional<UserEntry> resolved = identityResolver.resolve(provider, pushUser, pushToken);
        if (resolved.isEmpty()) {
            // CheckUserPushPermissionHook handles "user not registered" upstream; skip silently here
            log.debug("Push user '{}' could not be resolved — skipping identity verification", pushUser);
            return;
        }

        UserEntry user = resolved.get();
        List<String> registeredEmails = user.getEmails() != null ? user.getEmails() : List.of();
        Repository repo = rp.getRepository();
        List<String> violations = new ArrayList<>();

        for (ReceiveCommand cmd : commands) {
            if (cmd.getType() == ReceiveCommand.Type.DELETE) continue;
            try {
                List<Commit> commits = getCommits(repo, cmd);
                for (Commit commit : commits) {
                    collectViolations(commit, user.getUsername(), registeredEmails, violations);
                }
            } catch (Exception e) {
                log.error("Failed to verify identity for {}", cmd.getRefName(), e);
            }
        }

        if (violations.isEmpty()) {
            log.debug("Identity verification passed for push user '{}'", user.getUsername());
            recordPass();
            return;
        }

        if (mode == CommitConfig.IdentityVerificationMode.STRICT) {
            log.warn(
                    "Identity verification failed for push user '{}': {} mismatch(es)",
                    user.getUsername(),
                    violations.size());
            String detail = GitClientUtils.format(
                    sym(NO_ENTRY) + "  Push Blocked — Commit Identity Mismatch",
                    String.join("\n", violations),
                    RED,
                    null);
            validationContext.addIssue(
                    STEP_NAME, "Commit identity does not match push user " + user.getUsername(), detail);
        } else {
            // WARN mode — push proceeds; violations are surfaced via the validation summary, not
            // immediate per-message streaming, so they appear in the correct order with context.
            log.warn(
                    "Identity verification warnings for push user '{}': {} mismatch(es)",
                    user.getUsername(),
                    violations.size());
            pushContext.addStep(PushStep.builder()
                    .stepName(STEP_NAME)
                    .stepOrder(ORDER)
                    .status(StepStatus.PASS)
                    .content(String.join("\n", violations))
                    .build());
        }
    }

    private void collectViolations(
            Commit commit, String pushUsername, List<String> registeredEmails, List<String> violations) {
        String sha = abbrev(commit.getSha());
        boolean authorFlagged = false;

        if (commit.getAuthor() != null) {
            String email = commit.getAuthor().getEmail();
            if (email != null && !registeredEmails.contains(email)) {
                violations.add(sha + ": author <" + email + "> not registered to " + pushUsername);
                authorFlagged = true;
            }
        }

        if (commit.getCommitter() != null) {
            String email = commit.getCommitter().getEmail();
            if (email != null && !registeredEmails.contains(email)) {
                boolean sameAsAuthor = commit.getAuthor() != null
                        && email.equals(commit.getAuthor().getEmail());
                if (!sameAsAuthor) {
                    violations.add(sha + ": committer <" + email + "> not registered to " + pushUsername);
                } else if (!authorFlagged) {
                    violations.add(sha + ": author+committer <" + email + "> not registered to " + pushUsername);
                }
            }
        }
    }

    private void recordPass() {
        pushContext.addStep(PushStep.builder()
                .stepName(STEP_NAME)
                .stepOrder(ORDER)
                .status(StepStatus.PASS)
                .build());
    }

    private static String abbrev(String sha) {
        if (sha == null) return "?";
        return sha.substring(0, Math.min(7, sha.length()));
    }

    private static List<Commit> getCommits(Repository repo, ReceiveCommand cmd) throws Exception {
        if (ObjectId.zeroId().equals(cmd.getOldId())) {
            return List.of(CommitInspectionService.getCommitDetails(
                    repo, cmd.getNewId().name()));
        }
        return CommitInspectionService.getCommitRange(
                repo, cmd.getOldId().name(), cmd.getNewId().name());
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public String getName() {
        return "IdentityVerificationHook";
    }
}
