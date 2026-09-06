package com.rbc.fogwall.git;

import static org.junit.jupiter.api.Assertions.*;

import com.rbc.fogwall.config.BlockConfig;
import com.rbc.fogwall.config.DiffScanConfig;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiffScanningHookTest {

    @TempDir
    Path tempDir;

    Git git;
    Repository repo;

    @BeforeEach
    void setUp() throws Exception {
        git = Git.init().setDirectory(tempDir.toFile()).call();
        repo = git.getRepository();
        repo.getConfig().setBoolean("commit", null, "gpgsign", false);
        repo.getConfig().save();
    }

    private RevCommit commit(String filename, String content) throws Exception {
        File f = tempDir.resolve(filename).toFile();
        Files.writeString(f.toPath(), content + "\n");
        git.add().addFilepattern(".").call();
        return git.commit()
                .setAuthor(new PersonIdent("Dev", "dev@example.com"))
                .setCommitter(new PersonIdent("Dev", "dev@example.com"))
                .setMessage("add " + filename)
                .call();
    }

    private DiffScanConfig configWithLiteral(String literal) {
        return DiffScanConfig.builder()
                .block(BlockConfig.builder().literals(List.of(literal)).build())
                .build();
    }

    /** Run both diff generation and diff scanning hooks against a single UPDATE command. */
    private void runHooks(
            DiffScanConfig config,
            ValidationContext ctx,
            PushContext pushCtx,
            ObjectId oldId,
            ObjectId newId,
            String ref) {
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(oldId, newId, ref, ReceiveCommand.Type.UPDATE);

        new DiffGenerationHook(ctx, pushCtx).onPreReceive(rp, List.of(cmd));
        new DiffScanningHook(config, ctx, pushCtx).onPreReceive(rp, List.of(cmd));
    }

    // ---- aggregate scan ----

    @Test
    void aggregateScan_blockedContentInFinalDiff_fails() throws Exception {
        RevCommit c1 = commit("a.txt", "clean");
        RevCommit c2 = commit("b.txt", "SECRET_TOKEN=abc123");

        ValidationContext ctx = new ValidationContext();
        PushContext pushCtx = new PushContext();
        runHooks(configWithLiteral("SECRET_TOKEN"), ctx, pushCtx, c1.getId(), c2.getId(), "refs/heads/main");

        assertTrue(ctx.hasIssues(), "blocked term in final diff must be flagged");
    }

    @Test
    void aggregateScan_cleanDiff_passes() throws Exception {
        RevCommit c1 = commit("a.txt", "clean content");
        RevCommit c2 = commit("b.txt", "also clean");

        ValidationContext ctx = new ValidationContext();
        PushContext pushCtx = new PushContext();
        runHooks(configWithLiteral("SECRET_TOKEN"), ctx, pushCtx, c1.getId(), c2.getId(), "refs/heads/main");

        assertFalse(ctx.hasIssues());
    }

    // ---- per-commit scan (intermediate-commit bypass) ----

    /**
     * The smuggling scenario from issue #339: - Commit B adds a blocked term - Commit C removes it - Aggregate diff A→C
     * is clean - Per-commit scan of A→B must catch it
     */
    @Test
    void perCommitScan_secretAddedInIntermediateCommitThenRemoved_fails() throws Exception {
        RevCommit cA = commit("base.txt", "clean baseline");
        // Commit B: adds the blocked term
        RevCommit cB = commit("secret.txt", "SECRET_TOKEN=hunter2");
        // Commit C: removes it
        Files.delete(tempDir.resolve("secret.txt"));
        git.rm().addFilepattern("secret.txt").call();
        RevCommit cC = git.commit()
                .setAuthor(new PersonIdent("Dev", "dev@example.com"))
                .setCommitter(new PersonIdent("Dev", "dev@example.com"))
                .setMessage("remove secret")
                .call();

        ValidationContext ctx = new ValidationContext();
        PushContext pushCtx = new PushContext();
        runHooks(configWithLiteral("SECRET_TOKEN"), ctx, pushCtx, cA.getId(), cC.getId(), "refs/heads/main");

        assertTrue(
                ctx.hasIssues(),
                "blocked term in intermediate commit must be flagged even when removed by later commit");
        // Violation detail should identify which commit
        boolean mentionsCommit = ctx.getIssues().stream()
                .anyMatch(i -> i.detail().contains(cB.getName().substring(0, 7)));
        assertTrue(mentionsCommit, "violation detail must reference the offending commit SHA");
    }

    @Test
    void perCommitScan_multipleCleanCommits_passes() throws Exception {
        RevCommit c1 = commit("a.txt", "clean");
        commit("b.txt", "still clean");
        RevCommit c3 = commit("c.txt", "also clean");

        ValidationContext ctx = new ValidationContext();
        PushContext pushCtx = new PushContext();
        runHooks(configWithLiteral("SECRET_TOKEN"), ctx, pushCtx, c1.getId(), c3.getId(), "refs/heads/main");

        assertFalse(ctx.hasIssues());
    }

    @Test
    void perCommitScan_singleCommitPush_noDoubleReporting() throws Exception {
        // Single-commit push: per-commit loop skips (aggregate already covers it).
        // Violation should appear exactly once.
        RevCommit c1 = commit("a.txt", "clean");
        RevCommit c2 = commit("b.txt", "SECRET_TOKEN=abc");

        ValidationContext ctx = new ValidationContext();
        PushContext pushCtx = new PushContext();
        runHooks(configWithLiteral("SECRET_TOKEN"), ctx, pushCtx, c1.getId(), c2.getId(), "refs/heads/main");

        assertTrue(ctx.hasIssues());
        assertEquals(1, ctx.getIssues().size(), "single-commit push must not double-report via per-commit scan");
    }

    // ---- skip conditions ----

    @Test
    void deleteCommand_skipped() throws Exception {
        RevCommit c1 = commit("a.txt", "SECRET_TOKEN=x");

        ValidationContext ctx = new ValidationContext();
        PushContext pushCtx = new PushContext();
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd =
                new ReceiveCommand(c1.getId(), ObjectId.zeroId(), "refs/heads/main", ReceiveCommand.Type.DELETE);

        new DiffScanningHook(configWithLiteral("SECRET_TOKEN"), ctx, pushCtx).onPreReceive(rp, List.of(cmd));

        assertFalse(ctx.hasIssues(), "delete command must not trigger scanning");
    }

    @Test
    void noDiffInContext_skipsGracefully() throws Exception {
        RevCommit c1 = commit("a.txt", "clean");
        RevCommit c2 = commit("b.txt", "SECRET_TOKEN=x");

        ValidationContext ctx = new ValidationContext();
        PushContext pushCtx = new PushContext(); // no diff step — DiffGenerationHook not run
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);

        // Per-commit scan still runs even without an aggregate diff
        new DiffScanningHook(configWithLiteral("SECRET_TOKEN"), ctx, pushCtx).onPreReceive(rp, List.of(cmd));

        // Single-commit push: per-commit scan skips, no aggregate diff → no issues recorded
        assertFalse(ctx.hasIssues());
    }
}
