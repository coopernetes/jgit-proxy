package org.finos.gitproxy.validation;

import static org.finos.gitproxy.git.GitClient.AnsiColor.*;
import static org.finos.gitproxy.git.GitClient.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClient.sym;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.commons.validator.routines.EmailValidator;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.git.Commit;
import org.finos.gitproxy.git.GitClient;

/**
 * Validates that every author email in the pushed commits passes format checks and matches the configured domain/local
 * rules.
 */
@RequiredArgsConstructor
public class AuthorEmailCheck implements CommitCheck {

    private final CommitConfig config;

    @Override
    public List<Violation> check(List<Commit> commits) {
        Set<String> emails = commits.stream().map(c -> c.getAuthor().getEmail()).collect(Collectors.toSet());

        List<Violation> violations = new ArrayList<>();
        for (String email : emails) {
            String reason = violationReason(email);
            if (reason != null) {
                String title = sym(NO_ENTRY) + "  Push Blocked — Invalid Author Email";
                String message = sym(CROSS_MARK) + "  " + email + "\n\n" + reason + "\n\n"
                        + "Verify your Git email address:\n"
                        + "  git config user.email \"you@example.com\"";
                violations.add(new Violation(email, reason, GitClient.format(title, message, RED, null)));
            }
        }
        return violations;
    }

    /** Returns the reason the email is rejected, or {@code null} if it is allowed. */
    private String violationReason(String email) {
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
        String local = parts[0];
        String domain = parts[1];

        Pattern localBlock = config.getAuthor().getEmail().getLocal().getBlock();
        if (localBlock != null && localBlock.matcher(local).find()) {
            return "blocked local part (" + local + ")";
        }

        Pattern domainAllow = config.getAuthor().getEmail().getDomain().getAllow();
        if (domainAllow != null && !domainAllow.matcher(domain).find()) {
            return "domain not allowed (" + domain + ")";
        }

        return null;
    }
}
