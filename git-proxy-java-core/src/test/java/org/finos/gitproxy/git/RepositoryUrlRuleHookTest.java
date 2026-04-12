package org.finos.gitproxy.git;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.finos.gitproxy.db.model.AccessRule;
import org.finos.gitproxy.db.model.StepStatus;
import org.finos.gitproxy.provider.GitHubProvider;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.servlet.filter.UrlRuleFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryUrlRuleHookTest {

    private static final GitProxyProvider GITHUB = new GitHubProvider("/proxy");

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

    private ReceiveCommand makeCmd() {
        return new ReceiveCommand(ObjectId.zeroId(), ObjectId.zeroId(), "refs/heads/main");
    }

    @Test
    void openMode_noRules_recordsPass() {
        PushContext pushContext = new PushContext();
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = makeCmd();

        new RepositoryUrlRuleHook(pushContext).onPreReceive(rp, List.of(cmd));

        assertFalse(pushContext.getSteps().isEmpty());
        assertEquals("checkUrlRules", pushContext.getSteps().get(0).getStepName());
        assertEquals(StepStatus.PASS, pushContext.getSteps().get(0).getStatus());
        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, cmd.getResult());
    }

    @Test
    void withRepoSlug_allowRuleMatches_recordsPass() throws Exception {
        repo.getConfig().setString("gitproxy", null, "repoSlug", "/myorg/myrepo");
        repo.getConfig().save();

        var allowFilter = new UrlRuleFilter(100, GITHUB, List.of("myorg"), UrlRuleFilter.Target.OWNER);
        PushContext pushContext = new PushContext();
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = makeCmd();

        new RepositoryUrlRuleHook(List.of(allowFilter), null, GITHUB, null, pushContext).onPreReceive(rp, List.of(cmd));

        assertEquals(StepStatus.PASS, pushContext.getSteps().get(0).getStatus());
        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, cmd.getResult());
    }

    @Test
    void withRepoSlug_noMatchingAllowRule_rejectsCommand() throws Exception {
        repo.getConfig().setString("gitproxy", null, "repoSlug", "/myorg/myrepo");
        repo.getConfig().save();

        var allowFilter = new UrlRuleFilter(100, GITHUB, List.of("other-org"), UrlRuleFilter.Target.OWNER);
        PushContext pushContext = new PushContext();
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = makeCmd();

        new RepositoryUrlRuleHook(List.of(allowFilter), null, GITHUB, null, pushContext).onPreReceive(rp, List.of(cmd));

        assertEquals(StepStatus.FAIL, pushContext.getSteps().get(0).getStatus());
        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, cmd.getResult());
    }

    @Test
    void withRepoSlug_denyRuleMatches_rejectsCommand() throws Exception {
        repo.getConfig().setString("gitproxy", null, "repoSlug", "/myorg/myrepo");
        repo.getConfig().save();

        var denyFilter =
                new UrlRuleFilter(100, GITHUB, List.of("myorg"), UrlRuleFilter.Target.OWNER, AccessRule.Access.DENY);
        var allowFilter = new UrlRuleFilter(100, GITHUB, List.of("myorg"), UrlRuleFilter.Target.OWNER);
        PushContext pushContext = new PushContext();
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = makeCmd();

        new RepositoryUrlRuleHook(List.of(denyFilter, allowFilter), null, GITHUB, null, pushContext)
                .onPreReceive(rp, List.of(cmd));

        assertEquals(StepStatus.FAIL, pushContext.getSteps().get(0).getStatus());
        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, cmd.getResult());
    }

    @Test
    void fetchOnlyAllowRule_doesNotBlockPush() throws Exception {
        // FETCH-only allow rule must not cause pushes to fail with "not in allow list"
        repo.getConfig().setString("gitproxy", null, "repoSlug", "/myorg/myrepo");
        repo.getConfig().save();

        var fetchOnlyAllow = new UrlRuleFilter(
                100, java.util.Set.of(HttpOperation.FETCH), GITHUB, List.of("myorg"), UrlRuleFilter.Target.OWNER);
        PushContext pushContext = new PushContext();
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = makeCmd();

        new RepositoryUrlRuleHook(List.of(fetchOnlyAllow), null, GITHUB, null, pushContext)
                .onPreReceive(rp, List.of(cmd));

        // FETCH-only allow rules don't engage for push → open mode → pass
        assertEquals(StepStatus.PASS, pushContext.getSteps().get(0).getStatus());
    }

    @Test
    void fetchOnlyDenyRule_doesNotBlockPush() throws Exception {
        repo.getConfig().setString("gitproxy", null, "repoSlug", "/myorg/myrepo");
        repo.getConfig().save();

        var fetchDeny = new UrlRuleFilter(
                100,
                java.util.Set.of(HttpOperation.FETCH),
                GITHUB,
                List.of("myorg"),
                UrlRuleFilter.Target.OWNER,
                AccessRule.Access.DENY);
        var pushAllow = new UrlRuleFilter(100, GITHUB, List.of("myorg"), UrlRuleFilter.Target.OWNER);
        PushContext pushContext = new PushContext();
        ReceivePack rp = new ReceivePack(repo);
        ReceiveCommand cmd = makeCmd();

        new RepositoryUrlRuleHook(List.of(fetchDeny, pushAllow), null, GITHUB, null, pushContext)
                .onPreReceive(rp, List.of(cmd));

        assertEquals(StepStatus.PASS, pushContext.getSteps().get(0).getStatus());
        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, cmd.getResult());
    }
}
