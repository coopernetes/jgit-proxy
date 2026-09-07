package com.rbc.fogwall.config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.Builder;
import lombok.Data;

/**
 * A block list: literal strings matched case-insensitively, and compiled regex patterns.
 *
 * <p>Independent of what is being matched. Commit messages, pushed diffs, and proposal content each configure their own
 * list; the shape is the same in every case, and the runtime type carries compiled {@link Pattern}s rather than the
 * strings its YAML counterpart {@code BlockSettings} binds.
 */
@Data
@Builder
public class BlockConfig {

    /** Literal strings that are blocked. Matching is case-insensitive. */
    @Builder.Default
    private List<String> literals = new ArrayList<>();

    /** Compiled regex patterns that are blocked. */
    @Builder.Default
    private List<Pattern> patterns = new ArrayList<>();
}
