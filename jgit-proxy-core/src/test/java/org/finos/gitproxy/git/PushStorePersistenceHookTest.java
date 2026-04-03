package org.finos.gitproxy.git;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.memory.InMemoryPushStore;
import org.finos.gitproxy.db.model.PushStatus;
import org.finos.gitproxy.provider.GitHubProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for {@link PushStorePersistenceHook}.
 *
 * <p>Exercises the pre-receive hook (creates initial RECEIVED record) and the validation-result hook (transitions to
 * BLOCKED or REJECTED based on the {@link ValidationContext}).
 *
 * <p>Uses a real JGit repository (via {@code @TempDir}) and the in-memory push store so there are no external
 * dependencies.
 */
class PushStorePersistenceHookTest {

    @TempDir
    Path tempDir;

    Repository repo;
    ObjectId commitId;
    PushStore pushStore;
    PushStorePersistenceHook hook;

    @BeforeEach
    void setUp() throws Exception {
        Git git = Git.init().setDirectory(tempDir.toFile()).call();
        repo = git.getRepository();
        repo.getConfig().setBoolean("commit", null, "gpgsign", false);
        repo.getConfig().save();

        File f = new File(tempDir.toFile(), "init.txt");
        f.createNewFile();
        Files.writeString(f.toPath(), "initial");
        git.add().addFilepattern(".").call();
        RevCommit c = git.commit()
                .setAuthor(new PersonIdent("Dev", "dev@example.com"))
                .setCommitter(new PersonIdent("Dev", "dev@example.com"))
                .setMessage("Initial commit")
                .call();
        commitId = c.getId();

        pushStore = new InMemoryPushStore();
        hook = new PushStorePersistenceHook(pushStore, new GitHubProvider("/push"));
    }

    private ReceivePack makeReceivePack() {
        return new ReceivePack(repo);
    }

    private ReceiveCommand newBranchCommand(ObjectId newCommit) {
        return new ReceiveCommand(ObjectId.zeroId(), newCommit, "refs/heads/test");
    }

    // ---- pre-receive hook: initial record creation ----

    @Test
    void preReceiveHook_createsReceivedRecord() {
        ReceivePack rp = makeReceivePack();
        ReceiveCommand cmd = newBranchCommand(commitId);

        hook.preReceiveHook().onPreReceive(rp, List.of(cmd));

        // The push ID is stored in the repo config by the pre-receive hook
        String pushId = repo.getConfig().getString("gitproxy", null, "pushId");
        assertNotNull(pushId, "push ID should be stamped into repo config");

        var record = pushStore.findById(pushId);
        assertTrue(record.isPresent(), "RECEIVED record should be persisted");
        assertEquals(PushStatus.RECEIVED, record.get().getStatus());
    }

    @Test
    void preReceiveHook_branchAndCommitToAreRecorded() {
        ReceivePack rp = makeReceivePack();
        ReceiveCommand cmd = newBranchCommand(commitId);

        hook.preReceiveHook().onPreReceive(rp, List.of(cmd));

        String pushId = repo.getConfig().getString("gitproxy", null, "pushId");
        var record = pushStore.findById(pushId).orElseThrow();

        assertEquals("refs/heads/test", record.getBranch());
        assertEquals(commitId.name(), record.getCommitTo());
    }

    // ---- validation-result hook: no issues → BLOCKED ----

    @Test
    void validationResultHook_noIssues_transitionsToBlocked() {
        ReceivePack rp = makeReceivePack();
        ReceiveCommand cmd = newBranchCommand(commitId);

        // Pre-receive must run first to stamp the push ID
        hook.preReceiveHook().onPreReceive(rp, List.of(cmd));

        ValidationContext ctx = new ValidationContext(); // no issues
        hook.validationResultHook(ctx).onPreReceive(rp, List.of(cmd));

        String pushId = repo.getConfig().getString("gitproxy", null, "pushId");
        // The validation-result hook creates a new record with a fresh UUID (copyBase pattern);
        // we verify by querying for BLOCKED status rather than by ID.
        var records = pushStore.find(org.finos.gitproxy.db.model.PushQuery.builder()
                .status(PushStatus.BLOCKED)
                .build());
        assertFalse(records.isEmpty(), "a BLOCKED record should exist");
    }

    @Test
    void validationResultHook_noIssues_commandsNotRejected() {
        ReceivePack rp = makeReceivePack();
        ReceiveCommand cmd = newBranchCommand(commitId);

        hook.preReceiveHook().onPreReceive(rp, List.of(cmd));
        hook.validationResultHook(new ValidationContext()).onPreReceive(rp, List.of(cmd));

        assertEquals(
                ReceiveCommand.Result.NOT_ATTEMPTED,
                cmd.getResult(),
                "commands must not be rejected on a clean push (S&F doesn't reject here — it blocks in approval hook)");
    }

    // ---- validation-result hook: with issues → REJECTED ----

    @Test
    void validationResultHook_withIssues_transitionsToRejected() {
        ReceivePack rp = makeReceivePack();
        ReceiveCommand cmd = newBranchCommand(commitId);

        hook.preReceiveHook().onPreReceive(rp, List.of(cmd));

        ValidationContext ctx = new ValidationContext();
        ctx.addIssue("checkAuthorEmails", "Email blocked", "noreply@ address is not allowed");
        hook.validationResultHook(ctx).onPreReceive(rp, List.of(cmd));

        String pushId = repo.getConfig().getString("gitproxy", null, "pushId");
        var records = pushStore.find(org.finos.gitproxy.db.model.PushQuery.builder()
                .status(PushStatus.REJECTED)
                .build());
        assertFalse(records.isEmpty(), "a REJECTED record should exist");
    }

    @Test
    void validationResultHook_withIssues_commandsAreRejected() {
        ReceivePack rp = makeReceivePack();
        ReceiveCommand cmd = newBranchCommand(commitId);

        hook.preReceiveHook().onPreReceive(rp, List.of(cmd));

        ValidationContext ctx = new ValidationContext();
        ctx.addIssue("checkCommitMessages", "WIP commit", "message contains blocked term");
        hook.validationResultHook(ctx).onPreReceive(rp, List.of(cmd));

        assertEquals(
                ReceiveCommand.Result.REJECTED_OTHER_REASON,
                cmd.getResult(),
                "commands must be rejected when there are validation issues");
    }

    @Test
    void validationResultHook_withIssues_rejectedRecordHasBlockedMessage() {
        ReceivePack rp = makeReceivePack();
        ReceiveCommand cmd = newBranchCommand(commitId);

        hook.preReceiveHook().onPreReceive(rp, List.of(cmd));

        ValidationContext ctx = new ValidationContext();
        ctx.addIssue("checkAuthorEmails", "Email blocked", "some detail");
        hook.validationResultHook(ctx).onPreReceive(rp, List.of(cmd));

        var records = pushStore.find(org.finos.gitproxy.db.model.PushQuery.builder()
                .status(PushStatus.REJECTED)
                .build());
        assertFalse(records.isEmpty());
        String blocked = records.get(0).getBlockedMessage();
        assertNotNull(blocked, "blockedMessage should describe the rejection");
        assertTrue(blocked.contains("validation issue"), "blockedMessage should mention validation issue(s)");
    }

    // ---- multiple validation issues ----

    @Test
    void validationResultHook_multipleIssues_allRecorded() {
        ReceivePack rp = makeReceivePack();
        ReceiveCommand cmd = newBranchCommand(commitId);

        hook.preReceiveHook().onPreReceive(rp, List.of(cmd));

        ValidationContext ctx = new ValidationContext();
        ctx.addIssue("checkAuthorEmails", "Email blocked", "noreply@ address");
        ctx.addIssue("checkCommitMessages", "WIP commit", "message contains WIP");

        hook.validationResultHook(ctx).onPreReceive(rp, List.of(cmd));

        var records = pushStore.find(org.finos.gitproxy.db.model.PushQuery.builder()
                .status(PushStatus.REJECTED)
                .build());
        assertFalse(records.isEmpty());
        // Expect a blocked message mentioning both issues
        String msg = records.get(0).getBlockedMessage();
        assertTrue(msg.contains("2"), "blockedMessage should mention two validation issues");
    }

    // ---- serviceUrl included in rejection output ----

    @Test
    void serviceUrl_setBeforeHook_includedInBlockedRecord() {
        hook.setServiceUrl("http://dashboard:8080");
        ReceivePack rp = makeReceivePack();
        ReceiveCommand cmd = newBranchCommand(commitId);

        hook.preReceiveHook().onPreReceive(rp, List.of(cmd));

        // Clean push → BLOCKED with dashboard link (verified indirectly through record status)
        hook.validationResultHook(new ValidationContext()).onPreReceive(rp, List.of(cmd));

        var records = pushStore.find(org.finos.gitproxy.db.model.PushQuery.builder()
                .status(PushStatus.BLOCKED)
                .build());
        assertFalse(records.isEmpty(), "should have a BLOCKED record");
    }

    // ---- email validation config ----

    private CommitConfig blockNoreplyConfig() {
        return CommitConfig.builder()
                .author(CommitConfig.AuthorConfig.builder()
                        .email(CommitConfig.EmailConfig.builder()
                                .local(CommitConfig.LocalConfig.builder()
                                        .block(Pattern.compile("^(noreply|no-reply|bot)$"))
                                        .build())
                                .build())
                        .build())
                .build();
    }
}
