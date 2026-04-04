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
import org.finos.gitproxy.db.model.StepStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckHiddenCommitsHookTest {

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

    private RevCommit createCommit(String msg) throws Exception {
        File f = new File(tempDir.toFile(), UUID.randomUUID() + ".txt");
        Files.writeString(f.toPath(), msg);
        git.add().addFilepattern(".").call();
        return git.commit()
                .setAuthor(new PersonIdent("Dev", "dev@example.com"))
                .setCommitter(new PersonIdent("Dev", "dev@example.com"))
                .setMessage(msg)
                .call();
    }

    @Test
    void linearUpdate_noHiddenCommits_passes() throws Exception {
        // C1 → C2: straightforward update, C2 is in the introduced range
        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");

        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        PushContext pushContext = new PushContext();

        new CheckHiddenCommitsHook(pushContext).onPreReceive(rp, List.of(cmd));

        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, cmd.getResult());
        assertFalse(pushContext.getSteps().isEmpty());
        assertEquals(StepStatus.PASS, pushContext.getSteps().get(0).getStatus());
    }

    @Test
    void deleteCommand_skipped() throws Exception {
        RevCommit c1 = createCommit("init");

        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd =
                new ReceiveCommand(c1.getId(), ObjectId.zeroId(), "refs/heads/main", ReceiveCommand.Type.DELETE);

        new CheckHiddenCommitsHook(new PushContext()).onPreReceive(rp, List.of(cmd));

        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, cmd.getResult());
    }

    @Test
    void alreadyRejectedCommand_notTouched() throws Exception {
        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");

        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);
        cmd.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, "pre-rejected");

        new CheckHiddenCommitsHook(new PushContext()).onPreReceive(rp, List.of(cmd));

        assertEquals("pre-rejected", cmd.getMessage());
    }

    @Test
    void newBranch_firstPush_noExistingRefs_passes() throws Exception {
        // Fresh repo (no refs) - all commits in the new-branch push are introduced
        Git freshGit =
                Git.init().setDirectory(tempDir.resolve("fresh").toFile()).call();
        Repository freshRepo = freshGit.getRepository();
        freshRepo.getConfig().setBoolean("commit", null, "gpgsign", false);
        freshRepo.getConfig().save();

        File f = new File(tempDir.resolve("fresh").toFile(), "README.txt");
        Files.writeString(f.toPath(), "hello");
        freshGit.add().addFilepattern(".").call();
        RevCommit first = freshGit.commit()
                .setAuthor(new PersonIdent("Dev", "dev@example.com"))
                .setCommitter(new PersonIdent("Dev", "dev@example.com"))
                .setMessage("Initial commit")
                .call();

        ReceivePack rp = new ReceivePack(freshRepo);
        // Creating refs/heads/main for the first time - oldId is zeros
        ReceiveCommand cmd = new ReceiveCommand(ObjectId.zeroId(), first.getId(), "refs/heads/main");
        PushContext pushContext = new PushContext();

        new CheckHiddenCommitsHook(pushContext).onPreReceive(rp, List.of(cmd));

        // No existing refs → allNew == introduced → no hidden commits
        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, cmd.getResult());
    }

    @Test
    void lightweightTag_passes() throws Exception {
        // A lightweight tag points directly to a commit — no hidden-commit risk
        RevCommit c1 = createCommit("init");
        RevCommit tagged = createCommit("tagged");

        ReceivePack rp = new ReceivePack(repo);
        // Lightweight tag: newId IS the commit SHA
        ReceiveCommand cmd = new ReceiveCommand(ObjectId.zeroId(), tagged.getId(), "refs/tags/v1.0");
        PushContext pushContext = new PushContext();

        new CheckHiddenCommitsHook(pushContext).onPreReceive(rp, List.of(cmd));

        assertEquals(
                ReceiveCommand.Result.NOT_ATTEMPTED, cmd.getResult(), "Lightweight tag must pass hidden-commit check");
    }

    @Test
    void annotatedTag_passes() throws Exception {
        // Annotated tags point to a tag object (not a commit); the hook must dereference it
        RevCommit c1 = createCommit("init");
        RevCommit tagged = createCommit("tagged");

        // Create the annotated tag object in the repo
        org.eclipse.jgit.lib.ObjectInserter inserter = repo.newObjectInserter();
        org.eclipse.jgit.lib.TagBuilder tb = new org.eclipse.jgit.lib.TagBuilder();
        tb.setTag("v2.0");
        tb.setObjectId(tagged);
        tb.setTagger(new PersonIdent("Dev", "dev@example.com"));
        tb.setMessage("Release 2.0");
        ObjectId tagObjId = inserter.insert(tb);
        inserter.flush();

        ReceivePack rp = new ReceivePack(repo);
        // newId is the tag OBJECT, not the commit
        ReceiveCommand cmd = new ReceiveCommand(ObjectId.zeroId(), tagObjId, "refs/tags/v2.0");
        PushContext pushContext = new PushContext();

        new CheckHiddenCommitsHook(pushContext).onPreReceive(rp, List.of(cmd));

        assertEquals(
                ReceiveCommand.Result.NOT_ATTEMPTED,
                cmd.getResult(),
                "Annotated tag must pass hidden-commit check (tag object dereferenced to commit)");
    }

    @Test
    void nullPushContext_doesNotThrow() throws Exception {
        RevCommit c1 = createCommit("init");
        RevCommit c2 = createCommit("second");

        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(c1.getId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.UPDATE);

        // null pushContext path - hook should tolerate it (logs debug, skips addStep)
        assertDoesNotThrow(() -> new CheckHiddenCommitsHook(null).onPreReceive(rp, List.of(cmd)));
    }
}
