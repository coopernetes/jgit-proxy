package org.finos.gitproxy.git;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.db.model.StepStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommitMessageValidationHookTest {

    @TempDir
    Path tempDir;

    Repository repo;
    ObjectId initialCommitId;

    @BeforeEach
    void setUp() throws Exception {
        Git git = Git.init().setDirectory(tempDir.toFile()).call();
        repo = git.getRepository();
        // Disable GPG signing in the test repo to avoid UnsupportedSigningFormatException
        repo.getConfig().setBoolean("commit", null, "gpgsign", false);
        repo.getConfig().save();
        initialCommitId = createCommit(git, "Initial commit", "Dev User", "dev@example.com");
    }

    private ObjectId createCommit(Git git, String message, String name, String email) throws Exception {
        File f = new File(tempDir.toFile(), UUID.randomUUID() + ".txt");
        f.createNewFile();
        Files.writeString(f.toPath(), message);
        git.add().addFilepattern(".").call();
        RevCommit c = git.commit()
                .setAuthor(new PersonIdent(name, email))
                .setCommitter(new PersonIdent(name, email))
                .setMessage(message)
                .call();
        return c.getId();
    }

    private CommitConfig blockWipConfig() {
        return CommitConfig.builder()
                .message(CommitConfig.MessageConfig.builder()
                        .block(CommitConfig.BlockConfig.builder()
                                .literals(List.of("WIP", "DO NOT MERGE", "fixup!", "squash!"))
                                .patterns(List.of(Pattern.compile("(?i)(password|secret)\\s*[=:]\\s*\\S+")))
                                .build())
                        .build())
                .build();
    }

    private ReceivePack makeReceivePack() {
        return new ReceivePack(repo);
    }

    private ReceiveCommand newBranchCommand(ObjectId newCommit) {
        return new ReceiveCommand(ObjectId.zeroId(), newCommit, "refs/heads/test");
    }

    // ---- tests ----

    @Test
    void cleanMessage_noIssues() throws Exception {
        Git git = Git.open(tempDir.toFile());
        ObjectId cleanCommit = createCommit(git, "Add feature", "Dev", "dev@example.com");

        ValidationContext ctx = new ValidationContext();
        PushContext pushCtx = new PushContext();
        CommitMessageValidationHook hook = new CommitMessageValidationHook(blockWipConfig(), ctx, pushCtx);
        ReceivePack rp = makeReceivePack();

        hook.onPreReceive(rp, List.of(newBranchCommand(cleanCommit)));

        assertFalse(ctx.hasIssues(), "Clean message must not produce issues");
    }

    @Test
    void wipMessage_addsIssue() throws Exception {
        Git git = Git.open(tempDir.toFile());
        ObjectId wipCommit = createCommit(git, "WIP: work in progress", "Dev", "dev@example.com");

        ValidationContext ctx = new ValidationContext();
        PushContext pushCtx = new PushContext();
        CommitMessageValidationHook hook = new CommitMessageValidationHook(blockWipConfig(), ctx, pushCtx);
        ReceivePack rp = makeReceivePack();

        hook.onPreReceive(rp, List.of(newBranchCommand(wipCommit)));

        assertTrue(ctx.hasIssues(), "WIP message must produce an issue");
    }

    @Test
    void wipCaseInsensitive_addsIssue() throws Exception {
        Git git = Git.open(tempDir.toFile());
        // lowercase "wip" — the literal check is case-insensitive
        ObjectId wipCommit = createCommit(git, "wip: something", "Dev", "dev@example.com");

        ValidationContext ctx = new ValidationContext();
        PushContext pushCtx = new PushContext();
        CommitMessageValidationHook hook = new CommitMessageValidationHook(blockWipConfig(), ctx, pushCtx);
        ReceivePack rp = makeReceivePack();

        hook.onPreReceive(rp, List.of(newBranchCommand(wipCommit)));

        assertTrue(ctx.hasIssues(), "Case-insensitive WIP match must produce an issue");
    }

    @Test
    void doNotMerge_addsIssue() throws Exception {
        Git git = Git.open(tempDir.toFile());
        ObjectId doNotMerge = createCommit(git, "DO NOT MERGE", "Dev", "dev@example.com");

        ValidationContext ctx = new ValidationContext();
        PushContext pushCtx = new PushContext();
        CommitMessageValidationHook hook = new CommitMessageValidationHook(blockWipConfig(), ctx, pushCtx);
        ReceivePack rp = makeReceivePack();

        hook.onPreReceive(rp, List.of(newBranchCommand(doNotMerge)));

        assertTrue(ctx.hasIssues());
    }

    @Test
    void fixup_addsIssue() throws Exception {
        Git git = Git.open(tempDir.toFile());
        ObjectId fixup = createCommit(git, "fixup! previous", "Dev", "dev@example.com");

        ValidationContext ctx = new ValidationContext();
        PushContext pushCtx = new PushContext();
        CommitMessageValidationHook hook = new CommitMessageValidationHook(blockWipConfig(), ctx, pushCtx);
        ReceivePack rp = makeReceivePack();

        hook.onPreReceive(rp, List.of(newBranchCommand(fixup)));

        assertTrue(ctx.hasIssues());
    }

    @Test
    void passwordInMessage_addsIssue() throws Exception {
        Git git = Git.open(tempDir.toFile());
        ObjectId pwCommit = createCommit(git, "Config password=secret123", "Dev", "dev@example.com");

        ValidationContext ctx = new ValidationContext();
        PushContext pushCtx = new PushContext();
        CommitMessageValidationHook hook = new CommitMessageValidationHook(blockWipConfig(), ctx, pushCtx);
        ReceivePack rp = makeReceivePack();

        hook.onPreReceive(rp, List.of(newBranchCommand(pwCommit)));

        assertTrue(ctx.hasIssues(), "Password in message must produce an issue");
    }

    @Test
    void cleanMessage_recordsPassStep() throws Exception {
        Git git = Git.open(tempDir.toFile());
        ObjectId clean = createCommit(git, "Add unit tests", "Dev", "dev@example.com");

        ValidationContext ctx = new ValidationContext();
        PushContext pushCtx = new PushContext();
        CommitMessageValidationHook hook = new CommitMessageValidationHook(blockWipConfig(), ctx, pushCtx);
        ReceivePack rp = makeReceivePack();

        hook.onPreReceive(rp, List.of(newBranchCommand(clean)));

        assertFalse(pushCtx.getSteps().isEmpty());
        assertEquals(StepStatus.PASS, pushCtx.getSteps().get(0).getStatus());
    }

    @Test
    void blockedMessage_recordsFailStep() throws Exception {
        Git git = Git.open(tempDir.toFile());
        ObjectId wip = createCommit(git, "WIP: blocked", "Dev", "dev@example.com");

        ValidationContext ctx = new ValidationContext();
        PushContext pushCtx = new PushContext();
        CommitMessageValidationHook hook = new CommitMessageValidationHook(blockWipConfig(), ctx, pushCtx);
        ReceivePack rp = makeReceivePack();

        hook.onPreReceive(rp, List.of(newBranchCommand(wip)));

        assertFalse(pushCtx.getSteps().isEmpty());
        assertEquals(StepStatus.FAIL, pushCtx.getSteps().get(0).getStatus());
    }

    @Test
    void deleteCommand_skipped() throws Exception {
        ValidationContext ctx = new ValidationContext();
        PushContext pushCtx = new PushContext();
        CommitMessageValidationHook hook = new CommitMessageValidationHook(blockWipConfig(), ctx, pushCtx);
        ReceivePack rp = makeReceivePack();

        ReceiveCommand deleteCmd =
                new ReceiveCommand(initialCommitId, ObjectId.zeroId(), "refs/heads/test", ReceiveCommand.Type.DELETE);

        hook.onPreReceive(rp, List.of(deleteCmd));

        assertFalse(ctx.hasIssues(), "DELETE command must not trigger message validation");
    }

    @Test
    void noConfig_anyMessageAllowed() throws Exception {
        Git git = Git.open(tempDir.toFile());
        ObjectId wipCommit = createCommit(git, "WIP: even this passes", "Dev", "dev@example.com");

        ValidationContext ctx = new ValidationContext();
        PushContext pushCtx = new PushContext();
        CommitMessageValidationHook hook = new CommitMessageValidationHook(CommitConfig.defaultConfig(), ctx, pushCtx);
        ReceivePack rp = makeReceivePack();

        hook.onPreReceive(rp, List.of(newBranchCommand(wipCommit)));

        assertFalse(ctx.hasIssues(), "Default config must allow any message");
    }
}
