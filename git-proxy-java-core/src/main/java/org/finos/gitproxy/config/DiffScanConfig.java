package org.finos.gitproxy.config;

import lombok.Builder;
import lombok.Data;

/**
 * Runtime configuration for push-level diff content scanning. Holds compiled block patterns applied against added lines
 * in the push diff.
 *
 * <p>Distinct from {@link CommitConfig} which operates per-commit. This check runs once per push against the aggregate
 * diff.
 *
 * <p>Hot-reloadable via {@code POST /api/config/reload?section=diff-scan}.
 */
@Data
@Builder
public class DiffScanConfig {

    /** Block rules applied to added lines in the push diff. */
    @Builder.Default
    private CommitConfig.BlockConfig block = CommitConfig.BlockConfig.builder().build();

    public static DiffScanConfig defaultConfig() {
        return DiffScanConfig.builder().build();
    }
}
