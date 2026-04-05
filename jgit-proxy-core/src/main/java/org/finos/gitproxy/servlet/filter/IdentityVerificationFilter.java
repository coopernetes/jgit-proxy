package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.git.GitClientUtils.AnsiColor.*;
import static org.finos.gitproxy.git.GitClientUtils.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClientUtils.sym;
import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.db.model.StepStatus;
import org.finos.gitproxy.git.Commit;
import org.finos.gitproxy.git.GitClientUtils;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.service.PushIdentityResolver;
import org.finos.gitproxy.user.UserEntry;

/**
 * Transparent-proxy adapter for commit identity verification. Mirrors
 * {@link org.finos.gitproxy.git.IdentityVerificationHook} for the transparent proxy pipeline.
 *
 * <p>Checks that every pushed commit's author and committer email is registered to the authenticated push user.
 * Behaviour is controlled by {@link CommitConfig#getIdentityVerification()}:
 *
 * <ul>
 *   <li>{@code STRICT} — records an issue via {@link #recordIssue} so {@link ValidationSummaryFilter} blocks the push.
 *   <li>{@code WARN} — logs warnings but allows the push through (default).
 *   <li>{@code OFF} — skips the check entirely.
 * </ul>
 *
 * <p>Runs at order 160, after {@link CheckUserPushPermissionFilter} (150) and before content-validation filters (200+).
 */
@Slf4j
public class IdentityVerificationFilter extends AbstractGitProxyFilter {

    private static final int ORDER = 160;
    private static final String STEP_NAME = "identityVerification";

    private final PushIdentityResolver identityResolver;
    private final CommitConfig.IdentityVerificationMode mode;

    public IdentityVerificationFilter(
            PushIdentityResolver identityResolver, CommitConfig.IdentityVerificationMode mode) {
        super(ORDER, Set.of(HttpOperation.PUSH));
        this.identityResolver = identityResolver;
        this.mode = mode != null ? mode : CommitConfig.IdentityVerificationMode.WARN;
    }

    @Override
    public String getStepName() {
        return STEP_NAME;
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (mode == CommitConfig.IdentityVerificationMode.OFF) {
            log.debug("Identity verification disabled (mode=off)");
            return;
        }

        if (identityResolver == null) {
            log.debug("No identity resolver configured — skipping identity verification (open mode)");
            return;
        }

        var requestDetails = (GitRequestDetails) request.getAttribute(GIT_REQUEST_ATTR);
        if (requestDetails == null) {
            log.warn("GitRequestDetails not found in request attributes");
            return;
        }

        List<Commit> commits = requestDetails.getPushedCommits();
        if (commits == null || commits.isEmpty()) {
            log.debug("No commits to verify");
            return;
        }

        String[] userPass = extractBasicAuth(request);
        String pushUsername = userPass != null ? userPass[0] : null;
        String pushToken = userPass != null ? userPass[1] : null;

        Optional<UserEntry> resolved = identityResolver.resolve(requestDetails.getProvider(), pushUsername, pushToken);
        if (resolved.isEmpty()) {
            // CheckUserPushPermissionFilter handles "user not registered"; skip silently here
            log.debug("Push user '{}' could not be resolved — skipping identity verification", pushUsername);
            return;
        }

        UserEntry user = resolved.get();
        List<String> registeredEmails = user.getEmails() != null ? user.getEmails() : List.of();
        List<String> violations = new ArrayList<>();

        for (Commit commit : commits) {
            collectViolations(commit, user.getUsername(), registeredEmails, violations);
        }

        if (violations.isEmpty()) {
            log.debug("Identity verification passed for push user '{}'", user.getUsername());
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
            recordIssue(request, "Commit identity does not match push user " + user.getUsername(), detail);
        } else {
            // WARN mode — push proceeds but record violation count for the dashboard amber badge
            log.warn(
                    "Identity verification warnings for push user '{}': {} mismatch(es): {}",
                    user.getUsername(),
                    violations.size(),
                    violations);
            recordStep(
                    request,
                    StepStatus.PASS,
                    null,
                    violations.size() + " commit email(s) not registered to " + user.getUsername());
        }
    }

    private static void collectViolations(
            Commit commit, String pushUsername, List<String> registeredEmails, List<String> violations) {
        String sha = abbrev(commit.getSha());

        if (commit.getAuthor() != null) {
            String email = commit.getAuthor().getEmail();
            if (email != null && !registeredEmails.contains(email)) {
                violations.add(
                        sym(CROSS_MARK) + "  " + sha + ": author <" + email + "> is not registered to " + pushUsername);
            }
        }

        if (commit.getCommitter() != null) {
            String email = commit.getCommitter().getEmail();
            if (email != null && !registeredEmails.contains(email)) {
                boolean sameAsAuthor = commit.getAuthor() != null
                        && email.equals(commit.getAuthor().getEmail());
                if (!sameAsAuthor) {
                    violations.add(sym(CROSS_MARK) + "  " + sha + ": committer <" + email + "> is not registered to "
                            + pushUsername);
                }
            }
        }
    }

    private static String abbrev(String sha) {
        if (sha == null) return "?";
        return sha.substring(0, Math.min(7, sha.length()));
    }

    private static String[] extractBasicAuth(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Basic ")) return null;
        try {
            String decoded = new String(Base64.getDecoder()
                    .decode(authHeader.substring("Basic ".length()).trim()));
            int colon = decoded.indexOf(':');
            if (colon < 0) return null;
            return new String[] {decoded.substring(0, colon), decoded.substring(colon + 1)};
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
