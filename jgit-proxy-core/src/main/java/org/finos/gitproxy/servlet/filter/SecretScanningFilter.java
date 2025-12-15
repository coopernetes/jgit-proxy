package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyProviderServlet.GIT_REQUEST_ATTRIBUTE;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.config.SecretScanningConfig;
import org.finos.gitproxy.git.Commit;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;

/**
 * Filter that scans commit diffs for potential secrets and credentials. This filter uses configurable regex patterns to
 * detect API keys, passwords, private keys, and other sensitive data.
 */
@Slf4j
public class SecretScanningFilter extends AbstractGitProxyFilter {

    private static final int ORDER = 400;
    private final SecretScanningConfig config;
    private final List<Pattern> compiledPatterns;

    public SecretScanningFilter(SecretScanningConfig config) {
        super(ORDER, Set.of(HttpOperation.PUSH));
        this.config = config != null ? config : SecretScanningConfig.defaultConfig();
        this.compiledPatterns = compilePatterns(this.config.getPatterns());
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!config.isEnabled()) {
            log.debug("Secret scanning is disabled");
            return;
        }

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

        // Scan each commit message for secrets (as a simple implementation)
        // In a full implementation, you would parse the diff and scan file contents
        List<SecretMatch> secretsFound = new ArrayList<>();

        for (Commit commit : commits) {
            String message = commit.getMessage();
            if (message != null) {
                List<SecretMatch> matches = scanText(message, commit.getSha(), "commit message");
                secretsFound.addAll(matches);
            }
        }

        if (!secretsFound.isEmpty()) {
            log.warn("Potential secrets found in commits: {}", secretsFound.size());
            StringBuilder errorMessage = new StringBuilder(
                    "Your push has been blocked due to potential secrets or credentials detected:\n\n");
            for (SecretMatch match : secretsFound) {
                errorMessage
                        .append(String.format("  - Found in %s (commit: %s)\n", match.location, match.commitSha))
                        .append(String.format("    Pattern: %s\n", match.patternDescription))
                        .append(String.format("    Match: %s\n", maskSecret(match.matchedText)))
                        .append("\n");
            }
            errorMessage.append("Please remove any sensitive information and try again.");

            setResult(request, GitRequestDetails.GitResult.BLOCKED, "Potential secrets detected");
            sendGitError(request, response, errorMessage.toString());
            return;
        }

        log.debug("No secrets found in {} commits", commits.size());
    }

    /**
     * Scan text for potential secrets using configured patterns.
     *
     * @param text The text to scan
     * @param commitSha The commit SHA (for reporting)
     * @param location The location description (for reporting)
     * @return List of secret matches found
     */
    private List<SecretMatch> scanText(String text, String commitSha, String location) {
        List<SecretMatch> matches = new ArrayList<>();

        for (int i = 0; i < compiledPatterns.size(); i++) {
            Pattern pattern = compiledPatterns.get(i);
            var matcher = pattern.matcher(text);
            while (matcher.find()) {
                String matchedText = matcher.group();
                matches.add(new SecretMatch(
                        commitSha, location, config.getPatterns().get(i), matchedText));
            }
        }

        return matches;
    }

    /**
     * Compile regex patterns from configuration.
     *
     * @param patterns List of regex pattern strings
     * @return List of compiled patterns
     */
    private List<Pattern> compilePatterns(List<String> patterns) {
        List<Pattern> compiled = new ArrayList<>();
        for (String pattern : patterns) {
            try {
                compiled.add(Pattern.compile(pattern, Pattern.CASE_INSENSITIVE));
            } catch (PatternSyntaxException e) {
                log.error("Invalid secret scanning pattern: {}", pattern, e);
            }
        }
        return compiled;
    }

    /**
     * Mask a secret for safe logging (show first and last few characters).
     *
     * @param secret The secret to mask
     * @return The masked secret
     */
    private String maskSecret(String secret) {
        if (secret == null || secret.length() <= 8) {
            return "***";
        }
        return secret.substring(0, 4) + "..." + secret.substring(secret.length() - 4);
    }

    /** Data class for tracking secret matches. */
    private static class SecretMatch {
        final String commitSha;
        final String location;
        final String patternDescription;
        final String matchedText;

        SecretMatch(String commitSha, String location, String patternDescription, String matchedText) {
            this.commitSha = commitSha;
            this.location = location;
            this.patternDescription = patternDescription;
            this.matchedText = matchedText;
        }
    }
}
