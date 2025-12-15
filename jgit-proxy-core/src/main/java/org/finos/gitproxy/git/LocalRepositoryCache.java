package org.finos.gitproxy.git;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.URIish;

/**
 * Manages local clones of remote repositories for inspection and filtering. This service uses JGit to clone
 * repositories into temporary directories and maintains a cache of these clones.
 */
@Slf4j
public class LocalRepositoryCache {

    private final Path cacheDirectory;
    private final Map<String, CachedRepository> cache = new ConcurrentHashMap<>();
    private final int cloneDepth;
    private final boolean registerShutdownHook;

    /** Default constructor that uses system temp directory with shutdown hook. */
    public LocalRepositoryCache() throws IOException {
        this(Files.createTempDirectory("jgit-proxy-cache-"), 100, true);
    }

    /**
     * Constructor with custom cache directory - Spring-friendly (no shutdown hook).
     *
     * @param cacheDirectory The directory to use for caching repositories
     * @param registerShutdownHook Whether to register shutdown hook (false for Spring apps)
     */
    public LocalRepositoryCache(Path cacheDirectory, boolean registerShutdownHook) throws IOException {
        this(cacheDirectory, 100, registerShutdownHook);
    }

    /**
     * Full constructor with custom cache directory and clone depth.
     *
     * @param cacheDirectory The directory to use for caching repositories
     * @param cloneDepth The depth for shallow clones (0 for full clone)
     * @param registerShutdownHook Whether to register shutdown hook (false for Spring apps)
     */
    public LocalRepositoryCache(Path cacheDirectory, int cloneDepth, boolean registerShutdownHook) {
        this.cacheDirectory = cacheDirectory;
        this.cloneDepth = cloneDepth;
        this.registerShutdownHook = registerShutdownHook;
        log.info("Initialized LocalRepositoryCache at: {} with clone depth: {}", cacheDirectory, cloneDepth);
        // Register shutdown hook to clean up (skip for Spring apps that use @PreDestroy)
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
        String cacheKey = getCacheKey(remoteUrl);

        CachedRepository cached = cache.get(cacheKey);
        if (cached != null && cached.isValid()) {
            log.debug("Using cached repository for: {}", remoteUrl);
            return cached.repository;
        }

        // Clone or fetch the repository
        log.info("Cloning repository from: {}", remoteUrl);
        return cloneOrFetch(remoteUrl, cacheKey);
    }

    /**
     * Clone or fetch a repository.
     *
     * @param remoteUrl The remote repository URL
     * @param cacheKey The cache key for this repository
     * @return The local repository
     * @throws GitAPIException If git operations fail
     * @throws IOException If I/O operations fail
     */
    private synchronized Repository cloneOrFetch(String remoteUrl, String cacheKey)
            throws GitAPIException, IOException {
        // Double-check after acquiring lock
        CachedRepository cached = cache.get(cacheKey);
        if (cached != null && cached.isValid()) {
            return cached.repository;
        }

        File repoDir = new File(cacheDirectory.toFile(), cacheKey);

        Repository repository;
        if (repoDir.exists()) {
            log.debug("Repository directory exists, opening and fetching: {}", repoDir);
            Git git = Git.open(repoDir);
            // Fetch latest changes with depth if configured
            if (cloneDepth > 0) {
                git.fetch().setRemote("origin").setDepth(cloneDepth).call();
            } else {
                git.fetch().setRemote("origin").call();
            }
            repository = git.getRepository();
        } else {
            log.debug("Cloning repository to: {} with depth: {}", repoDir, cloneDepth);
            var cloneCommand = Git.cloneRepository()
                    .setURI(remoteUrl)
                    .setDirectory(repoDir)
                    .setBare(true);

            // Set shallow clone depth if configured
            if (cloneDepth > 0) {
                cloneCommand.setDepth(cloneDepth);
            }

            Git git = cloneCommand.call();
            repository = git.getRepository();
        }

        cache.put(cacheKey, new CachedRepository(repository, remoteUrl));
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

    /** Cached repository holder. */
    private static class CachedRepository {
        final Repository repository;
        final String remoteUrl;
        final long cachedAt;

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
