package com.rbc.fogwall.validation;

import com.rbc.fogwall.config.BlockConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Matches text against the configured blocked literals and patterns.
 *
 * <p>Separate from {@link BlockedContentDiffCheck} because the rules are about content, not about diffs: the same
 * blocked term is equally unwelcome in a pushed line and in the body of a pull request opened through the SCM API
 * proxy. The diff check supplies added lines and their file; the proposal path supplies a title or description. Only
 * what is fed in differs.
 */
public final class BlockedContentScanner {

    /** One match, with enough context to explain the decision after the fact. */
    public record Match(String rule, String location, String line) {

        /** Human-readable summary, used as both the violation title and its audit reason. */
        public String summary() {
            return location == null || location.isBlank() ? rule : rule + " in " + location;
        }
    }

    private BlockedContentScanner() {}

    /** Whether {@code block} would match anything at all — lets a caller skip the work entirely. */
    public static boolean isConfigured(BlockConfig block) {
        return block != null
                && !(block.getLiterals().isEmpty() && block.getPatterns().isEmpty());
    }

    /**
     * Scans one piece of text, reporting at most one match per rule. {@code location} names where the text came from —
     * a file path for a diff line, a field name such as {@code title} for a proposal — and may be {@code null}.
     */
    public static List<Match> scan(String text, String location, BlockConfig block) {
        if (text == null || text.isEmpty() || !isConfigured(block)) {
            return List.of();
        }
        List<Match> matches = new ArrayList<>();
        String lowered = text.toLowerCase(Locale.ROOT);
        for (String literal : block.getLiterals()) {
            if (lowered.contains(literal.toLowerCase(Locale.ROOT))) {
                matches.add(new Match("blocked term: \"" + literal + "\"", location, text.strip()));
            }
        }
        for (Pattern pattern : block.getPatterns()) {
            if (pattern.matcher(text).find()) {
                matches.add(new Match("blocked pattern: " + pattern.pattern(), location, text.strip()));
            }
        }
        return matches;
    }
}
