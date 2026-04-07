package org.finos.gitproxy.jetty.reload;

import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.config.CommitConfig;

/**
 * Thread-safe holder for hot-reloadable runtime configuration. Wraps an {@link AtomicReference} so that all servlet
 * filters and hooks that read from it automatically pick up the latest config on the next push — no servlet
 * re-registration required.
 *
 * <p>Only configuration that does not require servlet re-registration is held here (commit rules, auth settings).
 * Provider, server, and database changes log a warning and require a restart (or the UI-driven provider hot-swap from
 * coopernetes/jgit-proxy#75).
 *
 * <p>Filters and hooks access config via {@link #getCommitConfig()}, which is typically passed as a
 * {@code Supplier<CommitConfig>} method reference ({@code configHolder::getCommitConfig}).
 */
@Slf4j
public class ConfigHolder {

    private final AtomicReference<CommitConfig> commitConfig;

    public ConfigHolder(CommitConfig initial) {
        this.commitConfig = new AtomicReference<>(initial);
    }

    /** Returns the current live {@link CommitConfig}. Reads are always atomic and never block. */
    public CommitConfig getCommitConfig() {
        return commitConfig.get();
    }

    /**
     * Atomically replaces the live commit config. Called by {@link LiveConfigLoader} when a reload is triggered.
     * Filters and hooks reading on the next request will see the new value immediately.
     */
    public void update(CommitConfig newCommitConfig) {
        CommitConfig old = commitConfig.getAndSet(newCommitConfig);
        log.info(
                "CommitConfig reloaded: identityVerification={}, secretScanning.enabled={}",
                newCommitConfig.getIdentityVerification(),
                newCommitConfig.getSecretScanning().isEnabled());
        log.debug("Previous CommitConfig replaced: {}", old);
    }
}
