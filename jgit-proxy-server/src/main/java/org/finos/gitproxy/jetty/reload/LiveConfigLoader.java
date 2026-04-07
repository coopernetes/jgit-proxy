package org.finos.gitproxy.jetty.reload;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.jetty.config.GitProxyConfig;
import org.finos.gitproxy.jetty.config.GitProxyConfigLoader;
import org.finos.gitproxy.jetty.config.JettyConfigurationBuilder;
import org.finos.gitproxy.jetty.config.ReloadConfig;

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
 * <p>On every reload the full config POJO is rebuilt, but only {@link CommitConfig} (commit rules, auth settings) is
 * propagated to the live {@link ConfigHolder}. Changes to {@code providers}, {@code server}, {@code database}, or
 * {@code filters.whitelists} log a WARNING — those sections require a restart or the UI-driven provider hot-swap
 * (coopernetes/jgit-proxy#75).
 *
 * <p>A concurrent reload guard prevents overlapping reloads.
 */
@Slf4j
public class LiveConfigLoader {

    private final ConfigHolder configHolder;
    private final GitProxyConfig startupConfig;
    private final ReloadConfig reloadConfig;

    private final AtomicBoolean reloading = new AtomicBoolean(false);

    private Thread fileWatchThread;
    private ScheduledExecutorService gitPollScheduler;
    private ScheduledFuture<?> gitPollFuture;
    private Path gitRepoCache;

    public LiveConfigLoader(ConfigHolder configHolder, GitProxyConfig startupConfig, ReloadConfig reloadConfig) {
        this.configHolder = configHolder;
        this.startupConfig = startupConfig;
        this.reloadConfig = reloadConfig;
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
     * Manually triggers a reload from all configured sources. Called by the REST endpoint ({@code POST
     * /api/config/reload}).
     *
     * @return a brief human-readable description of what was reloaded
     */
    public String reload() {
        ReloadConfig.FileSourceConfig fileCfg = reloadConfig.getFile();
        ReloadConfig.GitSourceConfig gitCfg = reloadConfig.getGit();

        if (fileCfg.isEnabled() && !fileCfg.getPath().isBlank()) {
            reloadFromFile(Path.of(fileCfg.getPath()).toAbsolutePath());
            return "Reloaded from file: " + fileCfg.getPath();
        }

        if (gitCfg.isEnabled() && !gitCfg.getUrl().isBlank()) {
            reloadFromGit();
            return "Reloaded from git: " + gitCfg.getUrl() + " (" + gitCfg.getBranch() + ")";
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
                        log.info("Config file changed: {} — triggering reload", changed);
                        reloadFromFile(watchPath);
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

    private void reloadFromFile(Path overrideFile) {
        if (!reloading.compareAndSet(false, true)) {
            log.debug("Reload already in progress — skipping");
            return;
        }
        try {
            GitProxyConfig newConfig = GitProxyConfigLoader.loadWithOverride(overrideFile);
            applyReload(newConfig);
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
            // Perform an initial clone so the repo is ready for manual triggers
            gitPollScheduler = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("config-git-poller").factory());
            gitPollScheduler.submit(this::reloadFromGit);
            return;
        }
        log.info(
                "Starting git-source poller: url={} branch={} interval={}s",
                reloadConfig.getGit().getUrl(),
                reloadConfig.getGit().getBranch(),
                intervalSeconds);
        gitPollScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("config-git-poller").factory());
        gitPollFuture = gitPollScheduler.scheduleAtFixedRate(this::reloadFromGit, 0, intervalSeconds, TimeUnit.SECONDS);
    }

    private void reloadFromGit() {
        if (!reloading.compareAndSet(false, true)) {
            log.debug("Reload already in progress — skipping git poll");
            return;
        }
        try {
            Path yamlFile = fetchGitConfig();
            if (yamlFile != null) {
                GitProxyConfig newConfig = GitProxyConfigLoader.loadWithOverride(yamlFile);
                applyReload(newConfig);
            }
        } catch (Exception e) {
            log.error("Config reload from git failed: {}", e.getMessage(), e);
        } finally {
            reloading.set(false);
        }
    }

    /**
     * Clones the configured git repo (on first call) or pulls it (on subsequent calls). Returns the path to the
     * requested YAML file within the working tree, or {@code null} if the file doesn't exist.
     */
    private Path fetchGitConfig() throws GitAPIException, IOException {
        ReloadConfig.GitSourceConfig gitCfg = reloadConfig.getGit();

        if (gitRepoCache == null) {
            gitRepoCache = Files.createTempDirectory("jgit-proxy-config-git-");
            log.info("Cloning config repo {} branch={} into {}", gitCfg.getUrl(), gitCfg.getBranch(), gitRepoCache);
            Git.cloneRepository()
                    .setURI(gitCfg.getUrl())
                    .setBranch(gitCfg.getBranch())
                    .setDirectory(gitRepoCache.toFile())
                    .call()
                    .close();
            log.info("Config repo cloned successfully");
        } else {
            log.debug("Pulling config repo {}", gitCfg.getUrl());
            try (Git git = Git.open(gitRepoCache.toFile())) {
                git.pull().call();
            }
        }

        Path configFile = gitRepoCache.resolve(gitCfg.getFilePath());
        if (!Files.exists(configFile)) {
            log.warn("Config file {} not found in cloned repo at {}", gitCfg.getFilePath(), gitRepoCache);
            return null;
        }
        return configFile;
    }

    // -----------------------------------------------------------------------
    // Apply reload
    // -----------------------------------------------------------------------

    private void applyReload(GitProxyConfig newConfig) {
        CommitConfig newCommitConfig = buildCommitConfig(newConfig);
        configHolder.update(newCommitConfig);
        warnOnRestartRequired(newConfig);
    }

    private CommitConfig buildCommitConfig(GitProxyConfig cfg) {
        // Delegate to a fresh JettyConfigurationBuilder scoped to the new config — this reuses the
        // existing commit-config build logic without duplicating pattern compilation etc.
        return new JettyConfigurationBuilder(cfg).buildCommitConfig();
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
                    + " (see coopernetes/jgit-proxy#75 for UI-driven hot-swap)");
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
        if (!newConfig.getFilters().equals(startupConfig.getFilters())) {
            log.warn(
                    "Config reload: filters/whitelists changed — whitelist changes take effect on restart (provider hot-swap not yet supported)");
        }
        log.info("Config reload complete — commit rules, auth config now active");
    }
}
