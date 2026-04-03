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
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.finos.gitproxy.db.model.StepStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ForwardingPostReceiveHookTest {

    @TempDir
    Path localDir;

    @TempDir
    Path upstreamDir;

    Git localGit;
    Repository localRepo;
    String upstreamUrl;

    @BeforeEach
    void setUp() throws Exception {
        // Local (proxy-side) repo
        localGit = Git.init().setDirectory(localDir.toFile()).call();
        localRepo = localGit.getRepository();
        localRepo.getConfig().setBoolean("commit", null, "gpgsign", false);
        localRepo.getConfig().save();

        // Upstream bare repo
        Git.init().setBare(true).setDirectory(upstreamDir.toFile()).call();
        upstreamUrl = upstreamDir.toUri().toString();
    }

    private RevCommit createCommit(String msg) throws Exception {
        File f = new File(localDir.toFile(), UUID.randomUUID() + ".txt");
        Files.writeString(f.toPath(), msg);
        localGit.add().addFilepattern(".").call();
        return localGit.commit()
                .setAuthor(new PersonIdent("Dev", "dev@example.com"))
                .setCommitter(new PersonIdent("Dev", "dev@example.com"))
                .setMessage(msg)
                .call();
    }

    @Test
    void allCommandsRejected_noForward_recordsPassStep() {
        ReceivePack rp = new ReceivePack(localRepo);
        ReceiveCommand cmd = new ReceiveCommand(ObjectId.zeroId(), ObjectId.zeroId(), "refs/heads/main");
        // leave result as NOT_ATTEMPTED (not OK) → accepted list is empty

        PushContext pushContext = new PushContext();
        new ForwardingPostReceiveHook(null, null, pushContext).onPostReceive(rp, List.of(cmd));

        assertFalse(pushContext.getSteps().isEmpty());
        assertEquals(StepStatus.PASS, pushContext.getSteps().get(0).getStatus());
        assertTrue(pushContext.getSteps().get(0).getLogs().stream().anyMatch(l -> l.contains("No refs to forward")));
    }

    @Test
    void noUpstreamUrl_recordsFailStep() throws Exception {
        RevCommit c1 = createCommit("init");
        // No gitproxy.upstreamUrl set in repo config

        ReceivePack rp = new ReceivePack(localRepo);
        ReceiveCommand cmd = new ReceiveCommand(ObjectId.zeroId(), c1.getId(), "refs/heads/main");
        cmd.setResult(ReceiveCommand.Result.OK);

        PushContext pushContext = new PushContext();
        new ForwardingPostReceiveHook(null, null, pushContext).onPostReceive(rp, List.of(cmd));

        assertFalse(pushContext.getSteps().isEmpty());
        assertEquals(StepStatus.FAIL, pushContext.getSteps().get(0).getStatus());
        assertEquals("No upstream URL configured", pushContext.getSteps().get(0).getErrorMessage());
    }

    @Test
    void successfulForward_objectArrivesInUpstream() throws Exception {
        RevCommit c1 = createCommit("first commit");
        localRepo.getConfig().setString("gitproxy", null, "upstreamUrl", upstreamUrl);
        localRepo.getConfig().save();

        ReceivePack rp = new ReceivePack(localRepo);
        ReceiveCommand cmd =
                new ReceiveCommand(ObjectId.zeroId(), c1.getId(), "refs/heads/main", ReceiveCommand.Type.CREATE);
        cmd.setResult(ReceiveCommand.Result.OK);

        PushContext pushContext = new PushContext();
        new ForwardingPostReceiveHook(null, null, pushContext).onPostReceive(rp, List.of(cmd));

        // Verify the step was recorded as PASS
        assertFalse(pushContext.getSteps().isEmpty());
        assertEquals(StepStatus.PASS, pushContext.getSteps().get(0).getStatus());

        // Verify the commit actually landed in the upstream bare repo
        try (Repository upstream = Git.open(upstreamDir.toFile()).getRepository();
                RevWalk rw = new RevWalk(upstream)) {
            ObjectId upstreamHead = upstream.resolve("refs/heads/main");
            assertNotNull(upstreamHead, "refs/heads/main should exist in upstream after forward");
            assertEquals(c1.getId(), upstreamHead);
        }
    }

    @Test
    void multipleRefs_allForwarded() throws Exception {
        RevCommit c1 = createCommit("first");
        RevCommit c2 = createCommit("second on main");

        // Create a feature branch locally
        localGit.checkout().setCreateBranch(true).setName("feature").call();
        RevCommit c3 = createCommit("feature commit");
        localGit.checkout()
                .setName(localRepo.getFullBranch().replace("refs/heads/", ""))
                .call();

        localRepo.getConfig().setString("gitproxy", null, "upstreamUrl", upstreamUrl);
        localRepo.getConfig().save();

        ReceivePack rp = new ReceivePack(localRepo);
        ReceiveCommand mainCmd =
                new ReceiveCommand(ObjectId.zeroId(), c2.getId(), "refs/heads/main", ReceiveCommand.Type.CREATE);
        mainCmd.setResult(ReceiveCommand.Result.OK);
        ReceiveCommand featureCmd =
                new ReceiveCommand(ObjectId.zeroId(), c3.getId(), "refs/heads/feature", ReceiveCommand.Type.CREATE);
        featureCmd.setResult(ReceiveCommand.Result.OK);

        PushContext pushContext = new PushContext();
        new ForwardingPostReceiveHook(null, null, pushContext).onPostReceive(rp, List.of(mainCmd, featureCmd));

        assertEquals(StepStatus.PASS, pushContext.getSteps().get(0).getStatus());

        try (Repository upstream = Git.open(upstreamDir.toFile()).getRepository()) {
            assertNotNull(upstream.resolve("refs/heads/main"));
            assertNotNull(upstream.resolve("refs/heads/feature"));
        }
    }
}
