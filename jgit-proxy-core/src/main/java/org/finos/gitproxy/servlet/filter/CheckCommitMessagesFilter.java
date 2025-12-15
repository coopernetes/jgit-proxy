package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyProviderServlet.GIT_REQUEST_ATTRIBUTE;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.git.Commit;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;

/**
 * Filter that validates commit messages against configured blocked patterns and literals. This filter ensures commit
 * messages don't contain sensitive information or blocked content.
 */
@Slf4j
public class CheckCommitMessagesFilter extends AbstractGitProxyFilter {

    private static final int ORDER = 310;
    private final CommitConfig commitConfig;

    public CheckCommitMessagesFilter(CommitConfig commitConfig) {
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

        List<Commit> commits = requestDetails.getCommits();
        if (commits == null || commits.isEmpty()) {
            log.debug("No commits found in request details");
            return;
        }

        // Extract unique commit messages
        Set<String> uniqueCommitMessages =
                commits.stream().map(Commit::getMessage).collect(Collectors.toSet());

        // Validate each message
        List<String> illegalMessages = uniqueCommitMessages.stream()
                .filter(message -> !isMessageAllowed(message))
                .collect(Collectors.toList());

        if (!illegalMessages.isEmpty()) {
            log.warn("Illegal commit messages found: {}", illegalMessages);
            String errorMessage = String.format(
                    "Your push has been blocked.\n"
                            + "Please ensure your commit message(s) does not contain sensitive information or URLs.\n\n"
                            + "The following commit messages are illegal: %s",
                    illegalMessages);
            setResult(request, GitRequestDetails.GitResult.BLOCKED, "Illegal commit messages");
            sendGitError(request, response, errorMessage);
            return;
        }

        log.debug("All commit messages are legal: {}", uniqueCommitMessages.size());
    }

    /**
     * Check if a commit message is allowed based on configuration.
     *
     * @param commitMessage The commit message to check
     * @return true if the message is allowed, false otherwise
     */
    private boolean isMessageAllowed(String commitMessage) {
        // Empty messages are not allowed
        if (commitMessage == null || commitMessage.isEmpty()) {
            log.debug("Empty commit message detected");
            return false;
        }

        // Must be a string (should always be true in Java)
        if (!(commitMessage instanceof String)) {
            log.debug("Non-string commit message detected");
            return false;
        }

        List<String> blockedLiterals = commitConfig.getMessage().getBlock().getLiterals();
        List<String> blockedPatterns = commitConfig.getMessage().getBlock().getPatterns();

        // Check blocked literals (case-insensitive)
        for (String literal : blockedLiterals) {
            if (commitMessage.toLowerCase().contains(literal.toLowerCase())) {
                log.debug("Commit message contains blocked literal: {}", literal);
                return false;
            }
        }

        // Check blocked patterns
        for (String pattern : blockedPatterns) {
            try {
                Pattern compiledPattern = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
                if (compiledPattern.matcher(commitMessage).find()) {
                    log.debug("Commit message matches blocked pattern: {}", pattern);
                    return false;
                }
            } catch (PatternSyntaxException e) {
                log.error("Invalid regex pattern: {}", pattern, e);
                // Treat invalid patterns as not matching
                return false;
            }
        }

        return true;
    }
}
