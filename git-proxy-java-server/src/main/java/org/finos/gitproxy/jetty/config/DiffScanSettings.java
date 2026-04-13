package org.finos.gitproxy.jetty.config;

import lombok.Data;

/**
 * Binds the {@code diff-scan:} block in git-proxy.yml. Controls blocking of literal strings and regex patterns found in
 * push diff added-lines.
 *
 * <p>This is a push-level check (operates on the aggregate diff across all commits in the push), distinct from the
 * per-commit checks under {@code commit:}.
 */
@Data
public class DiffScanSettings {
    private BlockSettings block = new BlockSettings();
}
