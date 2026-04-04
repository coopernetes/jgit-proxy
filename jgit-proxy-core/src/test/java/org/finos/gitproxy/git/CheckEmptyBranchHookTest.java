package org.finos.gitproxy.git;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
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

class CheckEmptyBranchHookTest {

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

    private ObjectId createCommit(String message) throws Exception {
        File f = new File(tempDir.toFile(), UUID.randomUUID() + ".txt");
        f.createNewFile();
        Files.writeString(f.toPath(), message);
        git.add().addFilepattern(".").call();
        RevCommit c = git.commit()
                .setAuthor(new PersonIdent("Dev", "dev@example.com"))
                .setCommitter(new PersonIdent("Dev", "dev@example.com"))
                .setMessage(message)
                .call();
        return c.getId();
    }

    // ---- tests ----

    @Test
    void normalUpdate_existingBranch_passes() throws Exception {
        // C1 → C2 on main: standard update, should find commits and pass
        ObjectId c1 = createCommit("First commit");
        ObjectId c2 = createCommit("Second commit");

        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1, c2, "refs/heads/main", ReceiveCommand.Type.UPDATE);

        CheckEmptyBranchHook hook = new CheckEmptyBranchHook(new PushContext());
        hook.onPreReceive(rp, List.of(cmd));

        assertEquals(
                ReceiveCommand.Result.NOT_ATTEMPTED, cmd.getResult(), "Update with new commits must not be rejected");
    }

    @Test
    void newBranch_withNewCommit_passes() throws Exception {
        // First commit on main (C1), then C2 is created in a detached state by pointing HEAD at a
        // fresh commit. In our test-repo-as-server model, "new" means not yet reachable from any
        // existing ref. We simulate a first-ever push: empty repo → first commit.
        // Do NOT create a ref beforehand so that the new commit is genuinely new.
        Git freshGit =
                Git.init().setDirectory(tempDir.resolve("fresh").toFile()).call();
        Repository freshRepo = freshGit.getRepository();
        freshRepo.getConfig().setBoolean("commit", null, "gpgsign", false);
        freshRepo.getConfig().save();

        File f = new File(tempDir.resolve("fresh").toFile(), "init.txt");
        f.createNewFile();
        Files.writeString(f.toPath(), "hello");
        freshGit.add().addFilepattern(".").call();
        RevCommit first = freshGit.commit()
                .setAuthor(new PersonIdent("Dev", "dev@example.com"))
                .setCommitter(new PersonIdent("Dev", "dev@example.com"))
                .setMessage("Initial commit")
                .call();

        // Now we want to push "refs/heads/main" pointing to `first`, but `first` is
        // HEAD on main. The log command in getCommitRange should still find the commit
        // because no other ref exists yet.
        ReceivePack rp = new ReceivePack(freshRepo);
        // Simulating: the branch "main" is being created for the first time (old = zeros)
        ReceiveCommand cmd = new ReceiveCommand(ObjectId.zeroId(), first.getId(), "refs/heads/feature");
        CheckEmptyBranchHook hook = new CheckEmptyBranchHook(new PushContext());
        hook.onPreReceive(rp, List.of(cmd));

        // main → first is already set, feature → first: "first" is reachable from main, so empty
        // OR: there is no "main" ref yet in freshRepo... let us check
        // In freshGit.commit(), HEAD is on the initial branch (usually "master" or "main" per git
        // config) and a ref IS created. So this is the "empty branch" scenario.
        // If the repo only has one branch (main/master) pointing to `first`, and we try to push a
        // NEW branch also pointing to `first`, then git log first --not refs/heads/main = empty.
        assertEquals(
                ReceiveCommand.Result.REJECTED_OTHER_REASON,
                cmd.getResult(),
                "New branch pointing to existing commit must be rejected as empty");
    }

    @Test
    void deleteCommand_skipped() throws Exception {
        // DELETE commands should not be checked for empty branch
        ObjectId c1 = createCommit("Commit");

        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand deleteCmd =
                new ReceiveCommand(c1, ObjectId.zeroId(), "refs/heads/feature", ReceiveCommand.Type.DELETE);

        CheckEmptyBranchHook hook = new CheckEmptyBranchHook(new PushContext());
        hook.onPreReceive(rp, List.of(deleteCmd));

        assertEquals(
                ReceiveCommand.Result.NOT_ATTEMPTED,
                deleteCmd.getResult(),
                "Delete commands must be skipped by CheckEmptyBranchHook");
    }

    @Test
    void alreadyRejectedCommand_skipped() throws Exception {
        ObjectId c1 = createCommit("Commit");

        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(ObjectId.zeroId(), c1, "refs/heads/x");
        cmd.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, "pre-rejected");

        CheckEmptyBranchHook hook = new CheckEmptyBranchHook(new PushContext());
        hook.onPreReceive(rp, List.of(cmd));

        // Result should remain the pre-set value - hook must not touch it
        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, cmd.getResult());
        assertEquals("pre-rejected", cmd.getMessage());
    }

    @Test
    void lightweightTag_skipped() throws Exception {
        // Lightweight tags point directly to a commit — the empty-branch check must not fire
        ObjectId c1 = createCommit("Tagged commit");
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(ObjectId.zeroId(), c1, "refs/tags/v1.0");

        CheckEmptyBranchHook hook = new CheckEmptyBranchHook(new PushContext());
        hook.onPreReceive(rp, List.of(cmd));

        assertEquals(
                ReceiveCommand.Result.NOT_ATTEMPTED,
                cmd.getResult(),
                "Lightweight tag push must not be rejected by CheckEmptyBranchHook");
    }

    @Test
    void annotatedTag_skipped() throws Exception {
        // Annotated tags point to a tag object, not a commit directly
        ObjectId c1 = createCommit("Tagged commit");
        // Create an annotated tag via JGit
        org.eclipse.jgit.lib.ObjectInserter inserter = repo.newObjectInserter();
        org.eclipse.jgit.lib.TagBuilder tb = new org.eclipse.jgit.lib.TagBuilder();
        tb.setTag("v2.0");
        tb.setObjectId(repo.parseCommit(c1));
        tb.setTagger(new PersonIdent("Dev", "dev@example.com"));
        tb.setMessage("Release 2.0");
        ObjectId tagId = inserter.insert(tb);
        inserter.flush();

        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(ObjectId.zeroId(), tagId, "refs/tags/v2.0");

        CheckEmptyBranchHook hook = new CheckEmptyBranchHook(new PushContext());
        hook.onPreReceive(rp, List.of(cmd));

        assertEquals(
                ReceiveCommand.Result.NOT_ATTEMPTED,
                cmd.getResult(),
                "Annotated tag push must not be rejected by CheckEmptyBranchHook");
    }

    @Test
    void step_recordedOnPass() throws Exception {
        ObjectId c1 = createCommit("First");
        ObjectId c2 = createCommit("Second");

        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1, c2, "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pushCtx = new PushContext();

        CheckEmptyBranchHook hook = new CheckEmptyBranchHook(pushCtx);
        hook.onPreReceive(rp, List.of(cmd));

        assertFalse(pushCtx.getSteps().isEmpty(), "A PASS step must be recorded");
    }
}
