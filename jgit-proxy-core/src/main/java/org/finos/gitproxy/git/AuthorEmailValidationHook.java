package org.finos.gitproxy.git;

import static org.finos.gitproxy.git.GitClient.AnsiColor.*;
import static org.finos.gitproxy.git.GitClient.SymbolCodes.*;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.validator.routines.EmailValidator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.finos.gitproxy.config.CommitConfig;

/**
 * Pre-receive hook that validates commit author email addresses against configured patterns. Reports results via
 * sideband and records any issues in the shared {@link ValidationContext} — does not reject commands directly.
 */
@Slf4j
@RequiredArgsConstructor
public class AuthorEmailValidationHook implements PreReceiveHook {

    private final CommitConfig commitConfig;
    private final ValidationContext validationContext;

    @Override
    public void onPreReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
        rp.sendMessage(CYAN + "[git-proxy] " + KEY.emoji() + "  Checking author emails..." + RESET);

        Repository repo = rp.getRepository();

        for (ReceiveCommand cmd : commands) {
            if (cmd.getType() == ReceiveCommand.Type.DELETE) {
                continue;
            }

            try {
                List<Commit> commits = getCommits(repo, cmd);
                Set<String> emails =
                        commits.stream().map(c -> c.getAuthor().getEmail()).collect(Collectors.toSet());

                for (String email : emails) {
                    if (!isEmailAllowed(email)) {
                        String detail = describeRejection(email);
                        validationContext.addIssue("AuthorEmail", "Illegal author email: " + email, detail);
                        rp.sendMessage(
                                RED + "[git-proxy]   " + CROSS_MARK.emoji() + "  " + email + " — " + detail + RESET);
                    } else {
                        rp.sendMessage(GREEN + "[git-proxy]   " + HEAVY_CHECK_MARK.emoji() + "  " + email + RESET);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to validate author emails for {}", cmd.getRefName(), e);
                rp.sendMessage(YELLOW + "[git-proxy]   " + WARNING.emoji() + "  Could not validate emails: "
                        + e.getMessage() + RESET);
            }
        }
    }

    private List<Commit> getCommits(Repository repo, ReceiveCommand cmd) throws Exception {
        if (ObjectId.zeroId().equals(cmd.getOldId())) {
            return List.of(CommitInspectionService.getCommitDetails(
                    repo, cmd.getNewId().name()));
        }
        return CommitInspectionService.getCommitRange(
                repo, cmd.getOldId().name(), cmd.getNewId().name());
    }

    private boolean isEmailAllowed(String email) {
        if (email == null || email.isEmpty() || !EmailValidator.getInstance().isValid(email)) {
            return false;
        }

        String[] parts = email.split("@");
        if (parts.length != 2) {
            return false;
        }

        Pattern domainAllow = commitConfig.getAuthor().getEmail().getDomain().getAllow();
        if (domainAllow != null && !domainAllow.matcher(parts[1]).find()) {
            return false;
        }

        Pattern localBlock = commitConfig.getAuthor().getEmail().getLocal().getBlock();
        if (localBlock != null && localBlock.matcher(parts[0]).find()) {
            return false;
        }

        return true;
    }

    private String describeRejection(String email) {
        if (email == null || email.isEmpty()) {
            return "empty email";
        }
        if (!EmailValidator.getInstance().isValid(email)) {
            return "invalid email format";
        }

        String[] parts = email.split("@");
        if (parts.length != 2) {
            return "invalid email format";
        }

        Pattern localBlock = commitConfig.getAuthor().getEmail().getLocal().getBlock();
        if (localBlock != null && localBlock.matcher(parts[0]).find()) {
            return "blocked local part (" + parts[0] + ")";
        }

        Pattern domainAllow = commitConfig.getAuthor().getEmail().getDomain().getAllow();
        if (domainAllow != null && !domainAllow.matcher(parts[1]).find()) {
            return "domain not allowed (" + parts[1] + ")";
        }

        return "unknown reason";
    }
}
