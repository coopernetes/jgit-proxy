package org.finos.gitproxy.validation;

import static org.finos.gitproxy.git.GitClientUtils.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClientUtils.sym;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.commons.validator.routines.EmailValidator;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.git.Commit;

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
                String detail = sym(CROSS_MARK) + "  " + email + ": " + reason + "\n"
                        + "  \u2192 git config user.email \"you@example.com\"";
                violations.add(new Violation(email, reason, detail));
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
