package org.finos.gitproxy.validation;

import static org.finos.gitproxy.git.GitClient.AnsiColor.*;
import static org.finos.gitproxy.git.GitClient.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClient.sym;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.git.Commit;
import org.finos.gitproxy.git.GitClient;

/** Validates that no commit message contains blocked literals or patterns. */
@RequiredArgsConstructor
public class CommitMessageCheck implements CommitCheck {

    private final CommitConfig config;

    @Override
    public List<Violation> check(List<Commit> commits) {
        List<Violation> violations = new ArrayList<>();
        for (Commit commit : commits) {
            String reason = violationReason(commit.getMessage());
            if (reason != null) {
                String subject = commit.getMessage().lines().findFirst().orElse("(empty)");
                String title = sym(NO_ENTRY) + "  Push Blocked — Invalid Commit Message";
                String message = sym(CROSS_MARK) + "  " + subject + "\n\n" + reason + "\n\n" + sym(WARNING)
                        + "  Messages must not contain WIP, fixup!, squash!, DO NOT MERGE, or secrets.";
                violations.add(new Violation(subject, reason, GitClient.format(title, message, RED, null)));
            }
        }
        return violations;
    }

    /** Returns the reason the message is rejected, or {@code null} if it is allowed. */
    private String violationReason(String message) {
        if (message == null || message.isEmpty()) {
            return "empty commit message";
        }

        for (String literal : config.getMessage().getBlock().getLiterals()) {
            if (message.toLowerCase().contains(literal.toLowerCase())) {
                return "contains blocked term: \"" + literal + "\"";
            }
        }

        for (Pattern pattern : config.getMessage().getBlock().getPatterns()) {
            if (pattern.matcher(message).find()) {
                return "matches blocked pattern: " + pattern.pattern();
            }
        }

        return null;
    }
}
