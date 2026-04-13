package org.finos.gitproxy.jetty.reload;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.finos.gitproxy.db.RepoRegistry;
import org.finos.gitproxy.jetty.config.GitProxyConfig;
import org.finos.gitproxy.jetty.config.GitProxyConfigLoader;
import org.finos.gitproxy.jetty.config.JettyConfigurationBuilder;
import org.finos.gitproxy.jetty.config.ReloadConfig;
import org.finos.gitproxy.permission.RepoPermissionService;

/**
 * Manages hot-reloading of {@link ConfigHolder} at runtime, without restarting the server.
 *
 * <p>Two reload sources are supported (one or both may be active):
 *
 * <ol>
 *   <li><b>File watch</b> — a {@link WatchService} monitors a local filesystem path. When the file changes the proxy
 *       immediately reloads the overlay.
 *   <li><b>Git source</b> — a background thread clones (on first run) or pulls the configured git repository and reads
 *       a YAML file from the working tree on a fixed interval.
 * </ol>
 *
 * <p>Each reload can target a specific {@link Section} or {@link Section#ALL}. Provider, server, and database changes
 * log a WARNING — those sections require a restart (coopernetes/git-proxy-java#75).
 *
 * <p>A concurrent reload guard prevents overlapping reloads.
 */
@Slf4j
public class LiveConfigLoader {

    /**
     * Config sections that support hot-reload. Pass to {@link #reload(Section)} to reload only the specified section,
     * or use {@link #ALL} to reload everything.
     */
    public enum Section {
        COMMIT,
        DIFF_SCAN,
        SECRET_SCAN,
        RULES,
        PERMISSIONS,
        ATTESTATIONS,
        ALL;

        /** Parse a section name (case-insensitive, hyphens treated as underscores). */
        public static Section fromString(String value) {
            if (value == null) return ALL;
            return switch (value.trim().toLowerCase().replace('-', '_')) {
                case "commit" -> COMMIT;
                case "diff_scan" -> DIFF_SCAN;
                case "secret_scan" -> SECRET_SCAN;
                case "rules" -> RULES;
                case "permissions" -> PERMISSIONS;
                case "attestations" -> ATTESTATIONS;
                default -> ALL;
            };
        }
    }

    private final ConfigHolder configHolder;
    private final GitProxyConfig startupConfig;
    private final ReloadConfig reloadConfig;
    private final RepoRegistry repoRegistry;
    private final RepoPermissionService repoPermissionService;

    private final AtomicBoolean reloading = new AtomicBoolean(false);

    private Thread fileWatchThread;
    private ScheduledExecutorService gitPollScheduler;
    private ScheduledFuture<?> gitPollFuture;

    public LiveConfigLoader(
            ConfigHolder configHolder,
            GitProxyConfig startupConfig,
            ReloadConfig reloadConfig,
            RepoRegistry repoRegistry,
            RepoPermissionService repoPermissionService) {
        this.configHolder = configHolder;
        this.startupConfig = startupConfig;
        this.reloadConfig = reloadConfig;
        this.repoRegistry = repoRegistry;
        this.repoPermissionService = repoPermissionService;
    }

    /** Starts file-watch and/or git-poll threads based on {@link ReloadConfig}. */
    public void start() {
        if (reloadConfig.getFile().isEnabled()) {
            String path = reloadConfig.getFile().getPath();
            if (path == null || path.isBlank()) {
                log.warn("reload.file.enabled=true but reload.file.path is not set — file-watch disabled");
            } else {
                startFileWatch(Path.of(path).toAbsolutePath());
            }
        }

        if (reloadConfig.getGit().isEnabled()) {
            String url = reloadConfig.getGit().getUrl();
            if (url == null || url.isBlank()) {
                log.warn("reload.git.enabled=true but reload.git.url is not set — git-source disabled");
            } else {
                startGitPoller();
            }
        }
    }

    /** Stops all background threads cleanly. */
    public void stop() {
        if (fileWatchThread != null) {
            fileWatchThread.interrupt();
        }
        if (gitPollScheduler != null) {
            gitPollScheduler.shutdownNow();
        }
    }

    /**
     * Manually triggers a reload of all sections from configured sources. Equivalent to {@code reload(Section.ALL)}.
     *
     * @return a brief human-readable description of what was reloaded
     */
    public String reload() {
        return reload(Section.ALL);
    }

    /**
     * Manually triggers a reload of the specified section from configured sources. Called by the REST endpoint
     * ({@code POST /api/config/reload?section=<section>}).
     *
     * @return a brief human-readable description of what was reloaded
     */
    public String reload(Section section) {
        ReloadConfig.FileSourceConfig fileCfg = reloadConfig.getFile();
        ReloadConfig.GitSourceConfig gitCfg = reloadConfig.getGit();

        if (fileCfg.isEnabled() && !fileCfg.getPath().isBlank()) {
            reloadFromFile(Path.of(fileCfg.getPath()).toAbsolutePath(), section);
            return "Reloaded " + section.name().toLowerCase().replace('_', '-') + " from file: " + fileCfg.getPath();
        }

        if (gitCfg.isEnabled() && !gitCfg.getUrl().isBlank()) {
            reloadFromGit(section);
            return "Reloaded " + section.name().toLowerCase().replace('_', '-') + " from git: " + gitCfg.getUrl() + " ("
                    + gitCfg.getBranch() + ")";
        }

        return "No external reload sources configured — config unchanged";
    }

    // -----------------------------------------------------------------------
    // File watch
    // -----------------------------------------------------------------------

    private void startFileWatch(Path watchPath) {
        if (!Files.exists(watchPath)) {
            log.warn(
                    "reload.file.path={} does not exist — file-watch will not start until the file is created",
                    watchPath);
        }
        log.info("Starting file-watch on {}", watchPath);
        fileWatchThread = Thread.ofVirtual().name("config-file-watcher").start(() -> runFileWatch(watchPath));
    }

    private void runFileWatch(Path watchPath) {
        Path dir = watchPath.getParent();
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            dir.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);
            log.info("File-watch active on directory {} for {}", dir, watchPath.getFileName());
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key;
                try {
                    key = watcher.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                for (WatchEvent<?> event : key.pollEvents()) {
                    Path changed = dir.resolve((Path) event.context());
                    if (changed.equals(watchPath)) {
                        log.info("Config file changed: {} — triggering reload (all sections)", changed);
                        reloadFromFile(watchPath, Section.ALL);
                    }
                }
                if (!key.reset()) {
                    log.warn("File-watch key invalidated — directory may have been deleted");
                    break;
                }
            }
        } catch (IOException e) {
            log.error("File-watch failed to start on {}: {}", dir, e.getMessage(), e);
        }
    }

    private void reloadFromFile(Path overrideFile, Section section) {
        if (!reloading.compareAndSet(false, true)) {
            log.debug("Reload already in progress — skipping");
            return;
        }
        try {
            GitProxyConfig newConfig = GitProxyConfigLoader.loadWithOverride(overrideFile);
            applyReload(newConfig, section);
        } catch (Exception e) {
            log.error("Config reload from file {} failed: {}", overrideFile, e.getMessage(), e);
        } finally {
            reloading.set(false);
        }
    }

    // -----------------------------------------------------------------------
    // Git source
    // -----------------------------------------------------------------------

    private void startGitPoller() {
        int intervalSeconds = reloadConfig.getGit().getIntervalSeconds();
        if (intervalSeconds <= 0) {
            log.info(
                    "reload.git.interval-seconds=0 — git-source will only reload on POST /api/config/reload; no polling");
            gitPollScheduler = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("config-git-poller").factory());
            gitPollScheduler.submit(() -> reloadFromGit(Section.ALL));
            return;
        }
        log.info(
                "Starting git-source poller: url={} branch={} interval={}s",
                reloadConfig.getGit().getUrl(),
                reloadConfig.getGit().getBranch(),
                intervalSeconds);
        gitPollScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("config-git-poller").factory());
        gitPollFuture = gitPollScheduler.scheduleAtFixedRate(
                () -> reloadFromGit(Section.ALL), 0, intervalSeconds, TimeUnit.SECONDS);
    }

    private void reloadFromGit(Section section) {
        if (!reloading.compareAndSet(false, true)) {
            log.debug("Reload already in progress — skipping git poll");
            return;
        }
        Path cloneDir = null;
        try {
            cloneDir = Files.createTempDirectory("git-proxy-java-config-git-");
            Path yamlFile = fetchGitConfig(cloneDir);
            if (yamlFile != null) {
                GitProxyConfig newConfig = GitProxyConfigLoader.loadWithOverride(yamlFile);
                applyReload(newConfig, section);
            }
        } catch (Exception e) {
            log.error("Config reload from git failed: {}", e.getMessage(), e);
        } finally {
            reloading.set(false);
            deleteQuietly(cloneDir);
        }
    }

    /**
     * Clones the configured git repo into {@code cloneDir}, reads the requested YAML file, and returns its path.
     * Returns {@code null} if the file doesn't exist in the repo.
     *
     * <p>A fresh clone is performed on every call — no local state is retained between reloads. This avoids pull
     * failures caused by force-pushes on the remote branch.
     *
     * <p>Authentication is opt-in via environment variables — no config fields required:
     *
     * <ul>
     *   <li>{@code GITPROXY_RELOAD_GIT_AUTH_USERNAME} — git username (or a token username placeholder)
     *   <li>{@code GITPROXY_RELOAD_GIT_AUTH_PASSWORD} — personal access token or password
     * </ul>
     *
     * If neither variable is set the clone proceeds without credentials (suitable for public repos or SSH-based URLs
     * where the JVM's SSH agent handles auth).
     */
    private Path fetchGitConfig(Path cloneDir) throws GitAPIException, IOException {
        ReloadConfig.GitSourceConfig gitCfg = reloadConfig.getGit();
        UsernamePasswordCredentialsProvider creds = gitCredentials();

        log.info(
                "Cloning config repo {} branch={} into {} (auth={})",
                gitCfg.getUrl(),
                gitCfg.getBranch(),
                cloneDir,
                creds != null ? "yes" : "no");
        var clone = Git.cloneRepository()
                .setURI(gitCfg.getUrl())
                .setBranch(gitCfg.getBranch())
                .setDirectory(cloneDir.toFile())
                .setDepth(1);
        if (creds != null) clone.setCredentialsProvider(creds);
        clone.call().close();

        Path configFile = cloneDir.resolve(gitCfg.getFilePath());
        if (!Files.exists(configFile)) {
            log.warn("Config file {} not found in cloned repo at {}", gitCfg.getFilePath(), cloneDir);
            return null;
        }
        return configFile;
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            log.debug("Failed to delete temp clone dir {}: {}", dir, e.getMessage());
        }
    }

    /**
     * Reads {@code GITPROXY_RELOAD_GIT_AUTH_USERNAME} and {@code GITPROXY_RELOAD_GIT_AUTH_PASSWORD} from the
     * environment. Returns a credentials provider if both are set, or {@code null} if either is absent.
     */
    private static UsernamePasswordCredentialsProvider gitCredentials() {
        String username = System.getenv("GITPROXY_RELOAD_GIT_AUTH_USERNAME");
        String password = System.getenv("GITPROXY_RELOAD_GIT_AUTH_PASSWORD");
        if (username != null && !username.isBlank() && password != null && !password.isBlank()) {
            return new UsernamePasswordCredentialsProvider(username, password);
        }
        if ((username != null) != (password != null)) {
            log.warn("GITPROXY_RELOAD_GIT_AUTH_USERNAME and GITPROXY_RELOAD_GIT_AUTH_PASSWORD must both be set "
                    + "for git auth to work — proceeding without credentials");
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Apply reload
    // -----------------------------------------------------------------------

    private void applyReload(GitProxyConfig newConfig, Section section) {
        var builder = new JettyConfigurationBuilder(newConfig);
        // Validate all provider cross-references before applying any changes. If the new config has
        // a bad reference, this throws and the reload is aborted — the live config is not modified.
        builder.validateProviderReferences();

        switch (section) {
            case COMMIT -> reloadCommit(builder);
            case DIFF_SCAN -> reloadDiffScan(builder);
            case SECRET_SCAN -> reloadSecretScanning(builder);
            case RULES -> reloadRules(builder, newConfig);
            case PERMISSIONS -> reloadPermissions(builder, newConfig);
            case ATTESTATIONS -> reloadAttestations(builder, newConfig);
            case ALL -> {
                reloadCommit(builder);
                reloadDiffScan(builder);
                reloadSecretScanning(builder);
                reloadRules(builder, newConfig);
                reloadPermissions(builder, newConfig);
                reloadAttestations(builder, newConfig);
            }
        }

        warnOnRestartRequired(newConfig);
        log.info("Config reload complete — section: {}", section);
    }

    private void reloadCommit(JettyConfigurationBuilder builder) {
        configHolder.update(builder.buildCommitConfig());
    }

    private void reloadDiffScan(JettyConfigurationBuilder builder) {
        configHolder.update(builder.buildDiffScanConfig());
    }

    private void reloadSecretScanning(JettyConfigurationBuilder builder) {
        configHolder.update(builder.buildSecretScanConfig());
    }

    private void reloadRules(JettyConfigurationBuilder builder, GitProxyConfig newConfig) {
        if (repoRegistry == null) {
            log.warn("repoRegistry not available — rules reload skipped");
            return;
        }
        repoRegistry.seedFromConfig(builder.buildConfigRules(newConfig));
        log.info("Rules reloaded from config");
    }

    private void reloadPermissions(JettyConfigurationBuilder builder, GitProxyConfig newConfig) {
        if (repoPermissionService == null) {
            log.warn("repoPermissionService not available — permissions reload skipped");
            return;
        }
        repoPermissionService.seedFromConfig(builder.buildConfigPermissions(newConfig));
        log.info("Permissions reloaded from config");
    }

    private void reloadAttestations(JettyConfigurationBuilder builder, GitProxyConfig newConfig) {
        configHolder.update(builder.buildAttestations(newConfig));
    }

    /**
     * Compares the new config against the startup config and warns about sections that changed but require a restart to
     * take effect.
     */
    private void warnOnRestartRequired(GitProxyConfig newConfig) {
        if (!newConfig
                .getProviders()
                .keySet()
                .equals(startupConfig.getProviders().keySet())) {
            log.warn("Config reload: providers section changed — restart required for the new providers to take effect"
                    + " (see coopernetes/git-proxy-java#75 for UI-driven hot-swap)");
        }
        if (newConfig.getServer().getPort() != startupConfig.getServer().getPort()) {
            log.warn("Config reload: server.port changed — restart required");
        }
        if (!newConfig
                .getDatabase()
                .getType()
                .equals(startupConfig.getDatabase().getType())) {
            log.warn("Config reload: database.type changed — restart required");
        }
    }
}
