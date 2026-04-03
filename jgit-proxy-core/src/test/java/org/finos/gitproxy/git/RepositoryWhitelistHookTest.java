package org.finos.gitproxy.git;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.finos.gitproxy.db.model.StepStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryWhitelistHookTest {

    @TempDir
    Path tempDir;

    Repository repo;

    @BeforeEach
    void setUp() throws Exception {
        Git git = Git.init().setDirectory(tempDir.toFile()).call();
        repo = git.getRepository();
        repo.getConfig().setBoolean("commit", null, "gpgsign", false);
        repo.getConfig().save();
    }

    @Test
    void onPreReceive_recordsPassStep() {
        PushContext pushContext = new PushContext();
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(ObjectId.zeroId(), ObjectId.zeroId(), "refs/heads/main");

        new RepositoryWhitelistHook(pushContext).onPreReceive(rp, List.of(cmd));

        assertFalse(pushContext.getSteps().isEmpty());
        assertEquals("checkWhitelist", pushContext.getSteps().get(0).getStepName());
        assertEquals(StepStatus.PASS, pushContext.getSteps().get(0).getStatus());
    }

    @Test
    void onPreReceive_doesNotRejectCommands() {
        PushContext pushContext = new PushContext();
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = new ReceiveCommand(ObjectId.zeroId(), ObjectId.zeroId(), "refs/heads/main");

        new RepositoryWhitelistHook(pushContext).onPreReceive(rp, List.of(cmd));

        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, cmd.getResult());
    }
}
