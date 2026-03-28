package org.finos.gitproxy.jetty;

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
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.git.AuthorEmailValidationHook;
import org.finos.gitproxy.git.CommitMessageValidationHook;
import org.finos.gitproxy.git.PushContext;
import org.finos.gitproxy.git.ValidationContext;
import org.finos.gitproxy.git.ValidationVerifierHook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for the store-and-forward pre-receive hook chain. Tests the chain: AuthorEmailValidationHook →
 * CommitMessageValidationHook → ValidationVerifierHook.
 */
class StoreAndForwardHookChainTest {

    @TempDir
    Path tempDir;

    Repository repo;
    ObjectId initialCommitId;
    ObjectId latestCommitId;

    @BeforeEach
    void setUp() throws Exception {
        Git git = Git.init().setDirectory(tempDir.toFile()).call();
        repo = git.getRepository();
        // Disable GPG signing in the test repo to avoid UnsupportedSigningFormatException
        repo.getConfig().setBoolean("commit", null, "gpgsign", false);
        repo.getConfig().save();

        initialCommitId = createCommit(git, "Initial commit", "Dev User", "dev@example.com");
        latestCommitId = createCommit(git, "Add feature", "Dev User", "dev@example.com");
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

    private CommitConfig strictConfig() {
        return CommitConfig.builder()
                .author(CommitConfig.AuthorConfig.builder()
                        .email(CommitConfig.EmailConfig.builder()
                                .domain(CommitConfig.DomainConfig.builder()
                                        .allow(Pattern.compile("example\\.com$"))
                                        .build())
                                .local(CommitConfig.LocalConfig.builder()
                                        .block(Pattern.compile("^(noreply|bot|nobody)$"))
                                        .build())
                                .build())
                        .build())
                .message(CommitConfig.MessageConfig.builder()
                        .block(CommitConfig.BlockConfig.builder()
                                .literals(List.of("WIP", "DO NOT MERGE", "fixup!", "squash!"))
                                .build())
                        .build())
                .build();
    }

    private PreReceiveHook buildHookChain(CommitConfig config) {
        ValidationContext ctx = new ValidationContext();
        PushContext pushCtx = new PushContext();
        return chainHooks(
                new AuthorEmailValidationHook(config, ctx, pushCtx),
                new CommitMessageValidationHook(config, ctx, pushCtx),
                new ValidationVerifierHook(ctx));
    }

    private PreReceiveHook chainHooks(PreReceiveHook... hooks) {
        return (rp, cmds) -> {
            for (PreReceiveHook hook : hooks) {
                hook.onPreReceive(rp, cmds);
                if (cmds.stream().anyMatch(c -> c.getResult() != ReceiveCommand.Result.NOT_ATTEMPTED)) {
                    return;
                }
            }
        };
    }

    private ReceivePack makeReceivePack() {
        return new ReceivePack(repo);
    }

    private ReceiveCommand newBranchCommand(ObjectId newCommit) {
        return new ReceiveCommand(ObjectId.zeroId(), newCommit, "refs/heads/test");
    }

    private ReceiveCommand updateCommand(ObjectId oldCommit, ObjectId newCommit) {
        return new ReceiveCommand(oldCommit, newCommit, "refs/heads/main");
    }

    // ---- tests ----

    @Test
    void allValid_commandsAccepted() {
        PreReceiveHook chain = buildHookChain(strictConfig());
        ReceivePack rp = makeReceivePack();
        ReceiveCommand cmd = newBranchCommand(latestCommitId);

        chain.onPreReceive(rp, List.of(cmd));

        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, cmd.getResult(), "Valid push must not be rejected");
    }

    @Test
    void invalidEmail_commandsRejected() throws Exception {
        Git git = Git.open(tempDir.toFile());
        ObjectId badEmailCommit = createCommit(git, "Fix bug", "Outsider", "dev@gmail.com");

        PreReceiveHook chain = buildHookChain(strictConfig());
        ReceivePack rp = makeReceivePack();
        ReceiveCommand cmd = newBranchCommand(badEmailCommit);

        chain.onPreReceive(rp, List.of(cmd));

        assertEquals(
                ReceiveCommand.Result.REJECTED_OTHER_REASON, cmd.getResult(), "Invalid domain email must be rejected");
    }

    @Test
    void wipMessage_commandsRejected() throws Exception {
        Git git = Git.open(tempDir.toFile());
        ObjectId wipCommit = createCommit(git, "WIP: working on it", "Dev", "dev@example.com");

        PreReceiveHook chain = buildHookChain(strictConfig());
        ReceivePack rp = makeReceivePack();
        ReceiveCommand cmd = newBranchCommand(wipCommit);

        chain.onPreReceive(rp, List.of(cmd));

        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, cmd.getResult(), "WIP message must be rejected");
    }

    @Test
    void bothInvalid_commandsRejectedWithMultipleIssues() throws Exception {
        // Create a commit that violates both email (blocked local) and message (WIP)
        Git git = Git.open(tempDir.toFile());
        File f = new File(tempDir.toFile(), UUID.randomUUID() + ".txt");
        f.createNewFile();
        Files.writeString(f.toPath(), "bad commit");
        git.add().addFilepattern(".").call();
        RevCommit badCommit = git.commit()
                .setAuthor(new PersonIdent("Bot", "bot@example.com"))
                .setCommitter(new PersonIdent("Bot", "bot@example.com"))
                .setMessage("fixup! something bad")
                .call();

        // Use a fresh ValidationContext so we can inspect it
        ValidationContext ctx = new ValidationContext();
        PushContext pushCtx = new PushContext();
        PreReceiveHook chain = chainHooks(
                new AuthorEmailValidationHook(strictConfig(), ctx, pushCtx),
                new CommitMessageValidationHook(strictConfig(), ctx, pushCtx),
                new ValidationVerifierHook(ctx));

        ReceivePack rp = makeReceivePack();
        ReceiveCommand cmd = newBranchCommand(badCommit.getId());

        chain.onPreReceive(rp, List.of(cmd));

        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, cmd.getResult());
        // Both email and message hooks reported issues
        assertTrue(ctx.hasIssues());
        assertTrue(
                ctx.getIssues().size() >= 2,
                "Expected at least 2 issues (email + message), got: "
                        + ctx.getIssues().size());
    }

    @Test
    void branchDeletion_commandsAccepted() {
        PreReceiveHook chain = buildHookChain(strictConfig());
        ReceivePack rp = makeReceivePack();
        ReceiveCommand deleteCmd =
                new ReceiveCommand(latestCommitId, ObjectId.zeroId(), "refs/heads/test", ReceiveCommand.Type.DELETE);

        chain.onPreReceive(rp, List.of(deleteCmd));

        // DELETE must not be rejected by the validation hooks
        assertEquals(
                ReceiveCommand.Result.NOT_ATTEMPTED, deleteCmd.getResult(), "Branch deletion must not be rejected");
    }

    @Test
    void newBranch_singleValidCommit_notRejected() throws Exception {
        Git git = Git.open(tempDir.toFile());
        ObjectId cleanCommit = createCommit(git, "Add clean feature", "Dev", "dev@example.com");

        PreReceiveHook chain = buildHookChain(strictConfig());
        ReceivePack rp = makeReceivePack();
        // Old = zero → new branch
        ReceiveCommand cmd = new ReceiveCommand(ObjectId.zeroId(), cleanCommit, "refs/heads/new-feature");

        chain.onPreReceive(rp, List.of(cmd));

        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, cmd.getResult());
    }

    @Test
    void updateRange_validCommits_notRejected() {
        PreReceiveHook chain = buildHookChain(strictConfig());
        ReceivePack rp = makeReceivePack();
        ReceiveCommand cmd = updateCommand(initialCommitId, latestCommitId);

        chain.onPreReceive(rp, List.of(cmd));

        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, cmd.getResult());
    }
}
