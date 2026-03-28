package org.finos.gitproxy.git;

import static org.junit.jupiter.api.Assertions.*;

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

class ValidationVerifierHookTest {

    @TempDir
    Path tempDir;

    Repository repo;
    ObjectId commitId;

    @BeforeEach
    void setUp() throws Exception {
        Git git = Git.init().setDirectory(tempDir.toFile()).call();
        repo = git.getRepository();
        // Disable GPG signing in the test repo to avoid UnsupportedSigningFormatException
        repo.getConfig().setBoolean("commit", null, "gpgsign", false);
        repo.getConfig().save();
        File f = new File(tempDir.toFile(), "initial.txt");
        f.createNewFile();
        Files.writeString(f.toPath(), "initial");
        git.add().addFilepattern(".").call();
        RevCommit c = git.commit()
                .setAuthor(new PersonIdent("Dev", "dev@example.com"))
                .setCommitter(new PersonIdent("Dev", "dev@example.com"))
                .setMessage("Initial commit")
                .call();
        commitId = c.getId();
    }

    private ReceivePack makeReceivePack() {
        return new ReceivePack(repo);
    }

    private ReceiveCommand createCommand() {
        return new ReceiveCommand(ObjectId.zeroId(), commitId, "refs/heads/test");
    }

    // ---- tests ----

    @Test
    void noIssues_commandsNotRejected() {
        ValidationContext ctx = new ValidationContext(); // empty — no issues
        ValidationVerifierHook hook = new ValidationVerifierHook(ctx);
        ReceivePack rp = makeReceivePack();
        ReceiveCommand cmd = createCommand();

        hook.onPreReceive(rp, List.of(cmd));

        assertEquals(
                ReceiveCommand.Result.NOT_ATTEMPTED,
                cmd.getResult(),
                "Commands must remain NOT_ATTEMPTED when there are no issues");
    }

    @Test
    void withIssues_commandsRejected() {
        ValidationContext ctx = new ValidationContext();
        ctx.addIssue("TestHook", "Something went wrong", "Details here");
        ValidationVerifierHook hook = new ValidationVerifierHook(ctx);
        ReceivePack rp = makeReceivePack();
        ReceiveCommand cmd = createCommand();

        hook.onPreReceive(rp, List.of(cmd));

        assertEquals(
                ReceiveCommand.Result.REJECTED_OTHER_REASON,
                cmd.getResult(),
                "Commands must be rejected when there are validation issues");
    }

    @Test
    void multipleIssues_allCommandsRejected() {
        ValidationContext ctx = new ValidationContext();
        ctx.addIssue("EmailHook", "Bad email", "email@example.io not allowed");
        ctx.addIssue("MessageHook", "WIP message", "Message contains WIP");
        ValidationVerifierHook hook = new ValidationVerifierHook(ctx);
        ReceivePack rp = makeReceivePack();
        ReceiveCommand cmd1 = createCommand();
        ReceiveCommand cmd2 = new ReceiveCommand(
                ObjectId.zeroId(), ObjectId.fromString("0000000000000000000000000000000000000001"), "refs/heads/other");

        hook.onPreReceive(rp, List.of(cmd1, cmd2));

        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, cmd1.getResult());
        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, cmd2.getResult());
    }

    @Test
    void withIssues_alreadyRejectedCommand_notDoubleRejected() {
        ValidationContext ctx = new ValidationContext();
        ctx.addIssue("TestHook", "Issue", "Detail");
        ValidationVerifierHook hook = new ValidationVerifierHook(ctx);
        ReceivePack rp = makeReceivePack();

        // Pre-reject one command
        ReceiveCommand alreadyRejected = createCommand();
        alreadyRejected.setResult(ReceiveCommand.Result.REJECTED_MISSING_OBJECT);

        hook.onPreReceive(rp, List.of(alreadyRejected));

        // The hook only processes NOT_ATTEMPTED commands — already-rejected stays as-is
        assertEquals(
                ReceiveCommand.Result.REJECTED_MISSING_OBJECT,
                alreadyRejected.getResult(),
                "Already-rejected command result must not be overwritten");
    }

    @Test
    void noIssues_multipleCommands_noneRejected() {
        ValidationContext ctx = new ValidationContext();
        ValidationVerifierHook hook = new ValidationVerifierHook(ctx);
        ReceivePack rp = makeReceivePack();
        ReceiveCommand cmd1 = createCommand();
        ReceiveCommand cmd2 = createCommand();

        hook.onPreReceive(rp, List.of(cmd1, cmd2));

        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, cmd1.getResult());
        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, cmd2.getResult());
    }
}
