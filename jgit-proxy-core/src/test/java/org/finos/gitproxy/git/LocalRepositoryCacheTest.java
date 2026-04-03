package org.finos.gitproxy.git;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalRepositoryCacheTest {

    @TempDir
    Path remoteTempDir;

    @TempDir
    Path cacheTempDir;

    Git remoteGit;
    String remoteUrl;

    @BeforeEach
    void setUp() throws Exception {
        // Create a non-bare remote repo with a commit so it can be cloned
        remoteGit = Git.init().setDirectory(remoteTempDir.toFile()).call();
        remoteGit.getRepository().getConfig().setBoolean("commit", null, "gpgsign", false);
        remoteGit.getRepository().getConfig().save();

        File f = new File(remoteTempDir.toFile(), "README.txt");
        Files.writeString(f.toPath(), "hello");
        remoteGit.add().addFilepattern(".").call();
        remoteGit
                .commit()
                .setAuthor(new PersonIdent("Dev", "dev@example.com"))
                .setCommitter(new PersonIdent("Dev", "dev@example.com"))
                .setMessage("Initial commit")
                .call();

        remoteUrl = remoteTempDir.toUri().toString();
    }

    @Test
    void coldMiss_clonesRepository() throws Exception {
        LocalRepositoryCache cache = new LocalRepositoryCache(cacheTempDir, false);

        Repository repo = cache.getOrClone(remoteUrl);

        assertNotNull(repo);
        assertTrue(repo.getDirectory().exists(), "Cloned repo directory should exist on disk");
        repo.close();
    }

    @Test
    void warmHit_returnsCachedWithoutRecloning() throws Exception {
        LocalRepositoryCache cache = new LocalRepositoryCache(cacheTempDir, false);

        Repository first = cache.getOrClone(remoteUrl);
        File firstDir = first.getDirectory();
        first.close();

        Repository second = cache.getOrClone(remoteUrl);
        File secondDir = second.getDirectory();
        second.close();

        assertEquals(
                firstDir.getCanonicalPath(),
                secondDir.getCanonicalPath(),
                "Second call should return the same cached clone");
    }

    @Test
    void getCached_beforeClone_returnsNull() throws Exception {
        LocalRepositoryCache cache = new LocalRepositoryCache(cacheTempDir, false);

        assertNull(cache.getCached(remoteUrl));
    }

    @Test
    void getCached_afterClone_returnsRepo() throws Exception {
        LocalRepositoryCache cache = new LocalRepositoryCache(cacheTempDir, false);
        Repository cloned = cache.getOrClone(remoteUrl);
        cloned.close();

        assertNotNull(cache.getCached(remoteUrl));
    }

    @Test
    void remove_evictsFromCacheAndDeletesDisk() throws Exception {
        LocalRepositoryCache cache = new LocalRepositoryCache(cacheTempDir, false);
        Repository cloned = cache.getOrClone(remoteUrl);
        File cloneDir = cloned.getDirectory();
        cloned.close();

        cache.remove(remoteUrl);

        assertNull(cache.getCached(remoteUrl), "Entry should be gone from cache");
        assertFalse(cloneDir.exists(), "Clone directory should be deleted from disk");
    }

    @Test
    void clear_removesAllEntriesAndDirectory() throws Exception {
        // Clone two different repos into the same cache
        Path remote2Dir = cacheTempDir.getParent().resolve(UUID.randomUUID().toString());
        Files.createDirectories(remote2Dir);
        Git remote2 = Git.init().setDirectory(remote2Dir.toFile()).call();
        remote2.getRepository().getConfig().setBoolean("commit", null, "gpgsign", false);
        remote2.getRepository().getConfig().save();
        File f2 = new File(remote2Dir.toFile(), "file.txt");
        Files.writeString(f2.toPath(), "second");
        remote2.add().addFilepattern(".").call();
        remote2.commit()
                .setAuthor(new PersonIdent("Dev", "dev@example.com"))
                .setCommitter(new PersonIdent("Dev", "dev@example.com"))
                .setMessage("init")
                .call();
        String remoteUrl2 = remote2Dir.toUri().toString();

        LocalRepositoryCache cache = new LocalRepositoryCache(cacheTempDir, false);
        Repository r1 = cache.getOrClone(remoteUrl);
        r1.close();
        Repository r2 = cache.getOrClone(remoteUrl2);
        r2.close();

        cache.clear();

        assertNull(cache.getCached(remoteUrl));
        assertNull(cache.getCached(remoteUrl2));
        assertFalse(cacheTempDir.toFile().exists(), "Cache directory should be deleted after clear");
    }
}
