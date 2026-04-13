package org.finos.gitproxy.git;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;

/**
 * Manages local clones of remote repositories for inspection and filtering. This service uses JGit to clone
 * repositories into temporary directories and maintains a cache of these clones.
 *
 * <h2>Concurrency</h2>
 *
 * <p>Each cached repository has its own {@link ReentrantLock} ({@code CachedRepository.fetchLock}) that serializes
 * upstream fetches for that repository. The lock is acquired before any {@code git fetch} and released once the fetch
 * completes. A {@code fetchCooldownMs} guard ensures that a re-fetch is skipped when another thread has already
 * refreshed the mirror recently, avoiding redundant network round-trips.
 *
 * <p>Initial clones (first access for a URL) are still gated by an instance-level {@code synchronized} block to prevent
 * duplicate parallel clones of the same repository.
 *
 * <p>Note: upstream fetches are still possible while an {@code UploadPack} negotiation is in progress on the same
 * mirror. For deployments with high concurrent fetch traffic on shallow-cloned mirrors, consider using
 * {@code cloneDepth=0} for the serve cache to eliminate shallow-boundary reachability races entirely.
 */
@Slf4j
public class LocalRepositoryCache {

    private static final int DEFAULT_CLONE_DEPTH = 100;

    /**
     * Default minimum interval between upstream re-fetches for the same repository. Prevents concurrent serve requests
     * from each triggering a separate fetch when the mirror is already fresh.
     */
    private static final long DEFAULT_FETCH_COOLDOWN_MS = 30_000;

    private final Path cacheDirectory;
    private final Map<String, CachedRepository> cache = new ConcurrentHashMap<>();
    private final int cloneDepth;
    private final boolean registerShutdownHook;
    private final long fetchCooldownMs;

    /** Default constructor that uses system temp directory with shutdown hook. */
    public LocalRepositoryCache() throws IOException {
        this(Files.createTempDirectory("git-proxy-java-cache-"), DEFAULT_CLONE_DEPTH, true);
    }

    /**
     * Constructor with custom cache directory - Spring-friendly (no shutdown hook).
     *
     * @param cacheDirectory The directory to use for caching repositories
     * @param registerShutdownHook Whether to register shutdown hook (false for Spring apps)
     */
    public LocalRepositoryCache(Path cacheDirectory, boolean registerShutdownHook) throws IOException {
        this(cacheDirectory, DEFAULT_CLONE_DEPTH, registerShutdownHook);
    }

    /**
     * Full constructor with custom cache directory and clone depth.
     *
     * @param cacheDirectory The directory to use for caching repositories
     * @param cloneDepth The depth for shallow clones (0 for full clone)
     * @param registerShutdownHook Whether to register shutdown hook (false for Spring apps)
     */
    public LocalRepositoryCache(Path cacheDirectory, int cloneDepth, boolean registerShutdownHook) {
        this(cacheDirectory, cloneDepth, registerShutdownHook, DEFAULT_FETCH_COOLDOWN_MS);
    }

    /**
     * Full constructor with all options.
     *
     * @param cacheDirectory The directory to use for caching repositories
     * @param cloneDepth The depth for shallow clones (0 for full clone)
     * @param registerShutdownHook Whether to register shutdown hook (false for Spring apps)
     * @param fetchCooldownMs Minimum milliseconds between upstream re-fetches for the same repository
     */
    public LocalRepositoryCache(
            Path cacheDirectory, int cloneDepth, boolean registerShutdownHook, long fetchCooldownMs) {
        this.cacheDirectory = cacheDirectory;
        this.cloneDepth = cloneDepth;
        this.registerShutdownHook = registerShutdownHook;
        this.fetchCooldownMs = fetchCooldownMs;
        log.info("Initialized LocalRepositoryCache at: {} with clone depth: {}", cacheDirectory, cloneDepth);
        if (registerShutdownHook) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup));
        }
    }

    /**
     * Get or create a local clone of a remote repository.
     *
     * @param remoteUrl The URL of the remote repository
     * @return The local repository
     * @throws GitAPIException If git operations fail
     * @throws IOException If I/O operations fail
     */
    public Repository getOrClone(String remoteUrl) throws GitAPIException, IOException {
        return getOrClone(remoteUrl, null);
    }

    /**
     * Get or create a local clone of a remote repository, using the supplied credentials for clone and fetch.
     * Credentials are passed transiently to JGit — they are never written to disk.
     *
     * <p>On a cache hit, re-fetches from upstream to keep the local mirror fresh. The re-fetch is serialized via a
     * per-repository lock and skipped if the mirror was already refreshed within {@code fetchCooldownMs}.
     */
    public Repository getOrClone(String remoteUrl, CredentialsProvider credentials)
            throws GitAPIException, IOException {
        String cacheKey = getCacheKey(remoteUrl);

        CachedRepository cached = cache.get(cacheKey);
        if (cached != null && cached.isValid()) {
            log.debug("Using cached repository for: {}", remoteUrl);
            refreshIfStale(cached, cacheKey, credentials);
            cached.repository.incrementOpen();
            return cached.repository;
        }

        log.info("Cloning repository from: {}", remoteUrl);
        return cloneOrFetch(remoteUrl, cacheKey, credentials);
    }

    /**
     * Re-fetches from upstream if the mirror hasn't been refreshed within {@code fetchCooldownMs}.
     *
     * <p>Acquires the per-repository fetch lock before checking the cooldown so that concurrent callers for the same
     * repository serialize — the second caller will see the updated {@code lastFetchedAt} and skip the fetch. This
     * prevents two simultaneous JGit fetch operations against the same bare repository directory, which can corrupt
     * ref/pack state under concurrent access.
     */
    private void refreshIfStale(CachedRepository cached, String cacheKey, CredentialsProvider credentials)
            throws GitAPIException, IOException {
        cached.fetchLock.lock();
        try {
            if (System.currentTimeMillis() - cached.lastFetchedAt <= fetchCooldownMs) {
                log.debug(
                        "Skipping re-fetch for {} — mirror refreshed {}ms ago",
                        cacheKey,
                        System.currentTimeMillis() - cached.lastFetchedAt);
                return;
            }
            log.debug("Re-fetching upstream for cached repository: {}", cacheKey);
            try (Git git = Git.open(new File(cacheDirectory.toFile(), cacheKey))) {
                git.fetch()
                        .setRemote("origin")
                        .setCredentialsProvider(credentials)
                        .call();
            }
            cached.lastFetchedAt = System.currentTimeMillis();
        } finally {
            cached.fetchLock.unlock();
        }
    }

    /**
     * Clone or fetch a repository. Synchronized at the instance level to prevent duplicate parallel clones when
     * multiple threads race on first access for the same URL.
     */
    private synchronized Repository cloneOrFetch(String remoteUrl, String cacheKey, CredentialsProvider credentials)
            throws GitAPIException, IOException {
        // Double-check after acquiring lock — another thread may have cloned while we waited
        CachedRepository cached = cache.get(cacheKey);
        if (cached != null && cached.isValid()) {
            cached.repository.incrementOpen();
            return cached.repository;
        }

        File repoDir = new File(cacheDirectory.toFile(), cacheKey);

        Repository repository;
        if (repoDir.exists()) {
            log.debug("Repository directory exists, opening and fetching: {}", repoDir);
            Git git = Git.open(repoDir);
            var fetchCmd = git.fetch().setRemote("origin");
            if (credentials != null) fetchCmd.setCredentialsProvider(credentials);
            if (cloneDepth > 0) fetchCmd.setDepth(cloneDepth);
            fetchCmd.call();
            repository = git.getRepository();
        } else {
            log.debug("Cloning repository to: {} with depth: {}", repoDir, cloneDepth);
            var cloneCommand = Git.cloneRepository()
                    .setURI(remoteUrl)
                    .setDirectory(repoDir)
                    .setBare(true);
            if (credentials != null) cloneCommand.setCredentialsProvider(credentials);
            if (cloneDepth > 0) cloneCommand.setDepth(cloneDepth);

            Git git = cloneCommand.call();
            repository = git.getRepository();
        }

        var newCached = new CachedRepository(repository, remoteUrl);
        newCached.lastFetchedAt = System.currentTimeMillis();
        cache.put(cacheKey, newCached);
        return repository;
    }

    /**
     * Get a repository from cache without cloning if it doesn't exist.
     *
     * @param remoteUrl The URL of the remote repository
     * @return The local repository, or null if not cached
     */
    public Repository getCached(String remoteUrl) {
        String cacheKey = getCacheKey(remoteUrl);
        CachedRepository cached = cache.get(cacheKey);
        return (cached != null && cached.isValid()) ? cached.repository : null;
    }

    /**
     * Remove a repository from the cache and delete its local clone.
     *
     * @param remoteUrl The URL of the remote repository
     * @throws IOException If deletion fails
     */
    public void remove(String remoteUrl) throws IOException {
        String cacheKey = getCacheKey(remoteUrl);
        CachedRepository cached = cache.remove(cacheKey);
        if (cached != null) {
            cached.close();
            File repoDir = cached.repository.getDirectory();
            if (repoDir.exists()) {
                deleteDirectory(repoDir.toPath());
            }
        }
    }

    /**
     * Clear the entire cache and delete all local clones.
     *
     * @throws IOException If cleanup fails
     */
    public void clear() throws IOException {
        for (String key : cache.keySet()) {
            CachedRepository cached = cache.remove(key);
            if (cached != null) {
                cached.close();
            }
        }
        deleteDirectory(cacheDirectory);
    }

    /** Cleanup resources on shutdown. */
    private void cleanup() {
        try {
            log.info("Cleaning up LocalRepositoryCache");
            clear();
        } catch (IOException e) {
            log.error("Error cleaning up LocalRepositoryCache", e);
        }
    }

    /**
     * Generate a cache key from a remote URL.
     *
     * @param remoteUrl The remote URL
     * @return A safe cache key
     */
    private String getCacheKey(String remoteUrl) {
        try {
            URIish uri = new URIish(remoteUrl);
            String path = uri.getPath();
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            if (path.endsWith(".git")) {
                path = path.substring(0, path.length() - 4);
            }
            return path.replace("/", "_").replace("\\", "_");
        } catch (Exception e) {
            log.warn("Failed to parse remote URL, using hash as cache key: {}", remoteUrl);
            return String.valueOf(remoteUrl.hashCode());
        }
    }

    /**
     * Delete a directory recursively.
     *
     * @param directory The directory to delete
     * @throws IOException If deletion fails
     */
    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        Files.walk(directory)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }

    /** Cached repository holder with per-repo fetch serialization. */
    private static class CachedRepository {
        final Repository repository;
        final String remoteUrl;
        final long cachedAt;

        /**
         * Serializes upstream fetches for this repository. Prevents two concurrent JGit fetch operations against the
         * same bare repository directory, which can interleave pack/ref writes and cause {@code want <sha> not valid}
         * errors during UploadPack negotiation.
         */
        final ReentrantLock fetchLock = new ReentrantLock();

        /**
         * Timestamp of the last successful upstream fetch. Compared against {@code fetchCooldownMs} to avoid redundant
         * re-fetches when multiple concurrent requests arrive for the same mirror. Volatile so that the write from the
         * thread holding {@code fetchLock} is visible to all other threads after the lock is released.
         */
        volatile long lastFetchedAt = 0;

        CachedRepository(Repository repository, String remoteUrl) {
            this.repository = repository;
            this.remoteUrl = remoteUrl;
            this.cachedAt = System.currentTimeMillis();
        }

        boolean isValid() {
            return repository != null && repository.getDirectory().exists();
        }

        void close() {
            if (repository != null) {
                repository.close();
            }
        }
    }
}
