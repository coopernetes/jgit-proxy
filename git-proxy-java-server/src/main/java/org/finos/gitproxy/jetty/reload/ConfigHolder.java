package org.finos.gitproxy.jetty.reload;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.config.DiffScanConfig;
import org.finos.gitproxy.config.SecretScanConfig;
import org.finos.gitproxy.jetty.config.AttestationQuestion;

/**
 * Thread-safe holder for hot-reloadable runtime configuration. Wraps {@link AtomicReference}s so that all servlet
 * filters, hooks, and controllers that read from it automatically pick up the latest config on the next request — no
 * servlet re-registration required.
 *
 * <p>Holds four independent config objects, each reloadable separately:
 *
 * <ul>
 *   <li>{@link CommitConfig} — per-commit checks: identity verification, author email, commit message
 *   <li>{@link DiffScanConfig} — push-level diff content blocking
 *   <li>{@link SecretScanConfig} — push-level gitleaks secret scanning
 *   <li>Attestation questions — global reviewer prompts (applies to all providers in this release)
 * </ul>
 *
 * <p>Provider, server, and database changes log a warning and require a restart (or the UI-driven provider hot-swap
 * from coopernetes/git-proxy-java#75).
 *
 * <p>Filters, hooks and controllers access config via typed getters, typically passed as method references (e.g.
 * {@code configHolder::getCommitConfig}).
 */
@Slf4j
public class ConfigHolder {

    private final AtomicReference<CommitConfig> commitConfig;
    private final AtomicReference<DiffScanConfig> diffScanConfig;
    private final AtomicReference<SecretScanConfig> secretScanConfig;
    private final AtomicReference<List<AttestationQuestion>> attestations;

    public ConfigHolder(
            CommitConfig commitConfig,
            DiffScanConfig diffScanConfig,
            SecretScanConfig secretScanConfig,
            List<AttestationQuestion> attestations) {
        this.commitConfig = new AtomicReference<>(commitConfig);
        this.diffScanConfig = new AtomicReference<>(diffScanConfig);
        this.secretScanConfig = new AtomicReference<>(secretScanConfig);
        this.attestations = new AtomicReference<>(attestations);
    }

    /** Returns the current live {@link CommitConfig}. Reads are always atomic and never block. */
    public CommitConfig getCommitConfig() {
        return commitConfig.get();
    }

    /** Returns the current live {@link DiffScanConfig}. Reads are always atomic and never block. */
    public DiffScanConfig getDiffScanConfig() {
        return diffScanConfig.get();
    }

    /** Returns the current live {@link SecretScanConfig}. Reads are always atomic and never block. */
    public SecretScanConfig getSecretScanConfig() {
        return secretScanConfig.get();
    }

    /**
     * Returns the current global list of attestation questions shown to reviewers. Applies to all providers in this
     * release. Reads are always atomic and never block.
     */
    public List<AttestationQuestion> getAttestations() {
        return attestations.get();
    }

    /**
     * Atomically replaces the live commit config. Called by {@link LiveConfigLoader} when a {@code commit} section
     * reload is triggered.
     */
    public void update(CommitConfig newCommitConfig) {
        CommitConfig old = commitConfig.getAndSet(newCommitConfig);
        log.info("CommitConfig reloaded: identityVerification={}", newCommitConfig.getIdentityVerification());
        log.debug("Previous CommitConfig replaced: {}", old);
    }

    /**
     * Atomically replaces the live diff-scan config. Called by {@link LiveConfigLoader} when a {@code diff-scan}
     * section reload is triggered.
     */
    public void update(DiffScanConfig newDiffScanConfig) {
        DiffScanConfig old = diffScanConfig.getAndSet(newDiffScanConfig);
        log.info(
                "DiffScanConfig reloaded: literals={}, patterns={}",
                newDiffScanConfig.getBlock().getLiterals().size(),
                newDiffScanConfig.getBlock().getPatterns().size());
        log.debug("Previous DiffScanConfig replaced: {}", old);
    }

    /**
     * Atomically replaces the live secret-scan config. Called by {@link LiveConfigLoader} when a {@code secret-scan}
     * section reload is triggered.
     */
    public void update(SecretScanConfig newSecretScanConfig) {
        SecretScanConfig old = secretScanConfig.getAndSet(newSecretScanConfig);
        log.info("SecretScanConfig reloaded: enabled={}", newSecretScanConfig.isEnabled());
        log.debug("Previous SecretScanConfig replaced: {}", old);
    }

    /**
     * Atomically replaces the live attestation questions. Called by {@link LiveConfigLoader} when the
     * {@code attestations} section (or {@code all}) is reloaded.
     */
    public void update(List<AttestationQuestion> newAttestations) {
        List<AttestationQuestion> old = attestations.getAndSet(newAttestations);
        log.info("Attestations reloaded: {} question(s)", newAttestations.size());
        log.debug("Previous attestations replaced: {}", old);
    }
}
