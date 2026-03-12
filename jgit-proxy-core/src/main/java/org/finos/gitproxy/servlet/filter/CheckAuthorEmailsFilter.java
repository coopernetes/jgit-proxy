package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyProviderServlet.GIT_REQUEST_ATTRIBUTE;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.validator.routines.EmailValidator;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.git.Commit;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;

/**
 * Filter that validates commit author email addresses against configured patterns. This filter checks that all commit
 * author emails are valid and match allowed domain patterns, and don't match blocked local part patterns.
 *
 * <p>This filter runs at order 2100, which is in the built-in content filters range (2000-4999).
 */
@Slf4j
public class CheckAuthorEmailsFilter extends AbstractGitProxyFilter {

    private static final int ORDER = 2100;
    private final CommitConfig commitConfig;

    public CheckAuthorEmailsFilter(CommitConfig commitConfig) {
        super(ORDER, Set.of(HttpOperation.PUSH));
        this.commitConfig = commitConfig != null ? commitConfig : CommitConfig.defaultConfig();
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var requestDetails = (GitRequestDetails) request.getAttribute(GIT_REQUEST_ATTRIBUTE);
        if (requestDetails == null) {
            log.warn("GitRequestDetails not found in request attributes");
            return;
        }

        List<Commit> commits = requestDetails.getPushedCommits();
        if (commits == null || commits.isEmpty()) {
            log.debug("No commits found in request details");
            return;
        }

        // Extract unique author emails
        Set<String> uniqueAuthorEmails =
                commits.stream().map(c -> c.getAuthor().getEmail()).collect(Collectors.toSet());

        // Validate each email
        List<String> illegalEmails = uniqueAuthorEmails.stream()
                .filter(email -> !isEmailAllowed(email))
                .collect(Collectors.toList());

        if (!illegalEmails.isEmpty()) {
            log.warn("Illegal commit author emails found: {}", illegalEmails);
            String errorMessage = String.format(
                    "Your push has been blocked. The following commit author e-mails are illegal: %s\n"
                            + "Please verify your Git configured e-mail address is valid (e.g. john.smith@example.com)",
                    illegalEmails);
            setResult(request, GitRequestDetails.GitResult.BLOCKED, "Illegal author emails");
            sendGitError(request, response, errorMessage);
            return;
        }

        log.debug("All commit author emails are legal: {}", uniqueAuthorEmails);
    }

    /**
     * Check if an email address is allowed based on configuration.
     *
     * @param email The email address to check
     * @return true if the email is allowed, false otherwise
     */
    private boolean isEmailAllowed(String email) {
        if (email == null || email.isEmpty()) {
            return false;
        }

        // Validate email format
        if (!EmailValidator.getInstance().isValid(email)) {
            return false;
        }

        String[] parts = email.split("@");
        if (parts.length != 2) {
            return false;
        }

        String emailLocal = parts[0];
        String emailDomain = parts[1];

        // Check domain allow pattern
        Pattern domainAllowPattern =
                commitConfig.getAuthor().getEmail().getDomain().getAllow();
        if (domainAllowPattern != null) {
            if (!domainAllowPattern.matcher(emailDomain).find()) {
                return false;
            }
        }

        // Check local part block pattern
        Pattern localBlockPattern =
                commitConfig.getAuthor().getEmail().getLocal().getBlock();
        if (localBlockPattern != null) {
            if (localBlockPattern.matcher(emailLocal).find()) {
                return false;
            }
        }

        return true;
    }
}
