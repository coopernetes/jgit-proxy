package org.finos.gitproxy.git;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.finos.gitproxy.db.memory.InMemoryUrlRuleRegistry;
import org.finos.gitproxy.db.model.AccessRule;
import org.finos.gitproxy.db.model.StepStatus;
import org.finos.gitproxy.provider.GitHubProvider;
import org.finos.gitproxy.provider.GitProxyProvider;
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

    private RepositoryUrlRuleHook hookWith(AccessRule... rules) throws Exception {
        var registry = new InMemoryUrlRuleRegistry();
        for (AccessRule r : rules) registry.save(r);
        return new RepositoryUrlRuleHook(registry, GITHUB, null, new PushContext());
    }

    @Test
    void noRepoSlug_blocksWithFailClosed() {
        var pushContext = new PushContext();
        var hook = new RepositoryUrlRuleHook(new InMemoryUrlRuleRegistry(), GITHUB, null, pushContext);
        var cmd = makeCmd();

        hook.onPreReceive(new ReceivePack(repo), List.of(cmd));

        assertFalse(pushContext.getSteps().isEmpty());
        assertEquals("checkUrlRules", pushContext.getSteps().get(0).getStepName());
        assertEquals(StepStatus.FAIL, pushContext.getSteps().get(0).getStatus());
    }

    @Test
    void withRepoSlug_allowRuleMatches_recordsPass() throws Exception {

        var allowRule = AccessRule.builder()
                .ruleOrder(100)
                .access(AccessRule.Access.ALLOW)
                .operations(AccessRule.Operations.BOTH)
                .owner("myorg")
                .build();
        var pushContext = new PushContext();
        pushContext.setRepoSlug("/myorg/myrepo");
        var registry = new InMemoryUrlRuleRegistry();
        registry.save(allowRule);
        var hook = new RepositoryUrlRuleHook(registry, GITHUB, null, pushContext);
        var cmd = makeCmd();

        hook.onPreReceive(new ReceivePack(repo), List.of(cmd));

        assertEquals(StepStatus.PASS, pushContext.getSteps().get(0).getStatus());
        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, cmd.getResult());
    }

    @Test
    void withRepoSlug_noMatchingAllowRule_rejectsCommand() throws Exception {

        var allowRule = AccessRule.builder()
                .ruleOrder(100)
                .access(AccessRule.Access.ALLOW)
                .operations(AccessRule.Operations.BOTH)
                .owner("other-org")
                .build();
        var pushContext = new PushContext();
        pushContext.setRepoSlug("/myorg/myrepo");
        var registry = new InMemoryUrlRuleRegistry();
        registry.save(allowRule);
        var hook = new RepositoryUrlRuleHook(registry, GITHUB, null, pushContext);
        var cmd = makeCmd();

        hook.onPreReceive(new ReceivePack(repo), List.of(cmd));

        assertEquals(StepStatus.FAIL, pushContext.getSteps().get(0).getStatus());
        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, cmd.getResult());
    }

    @Test
    void withRepoSlug_denyRuleAtLowerOrder_rejectsEvenWithAllowRule() throws Exception {

        var denyRule = AccessRule.builder()
                .ruleOrder(100)
                .access(AccessRule.Access.DENY)
                .operations(AccessRule.Operations.BOTH)
                .owner("myorg")
                .build();
        var allowRule = AccessRule.builder()
                .ruleOrder(200)
                .access(AccessRule.Access.ALLOW)
                .operations(AccessRule.Operations.BOTH)
                .owner("myorg")
                .build();
        var pushContext = new PushContext();
        pushContext.setRepoSlug("/myorg/myrepo");
        var registry = new InMemoryUrlRuleRegistry();
        registry.save(denyRule);
        registry.save(allowRule);
        var hook = new RepositoryUrlRuleHook(registry, GITHUB, null, pushContext);
        var cmd = makeCmd();

        hook.onPreReceive(new ReceivePack(repo), List.of(cmd));

        assertEquals(StepStatus.FAIL, pushContext.getSteps().get(0).getStatus());
        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, cmd.getResult());
    }

    @Test
    void fetchOnlyAllowRule_doesNotEngageForPush() throws Exception {

        var fetchOnlyAllow = AccessRule.builder()
                .ruleOrder(100)
                .access(AccessRule.Access.ALLOW)
                .operations(AccessRule.Operations.FETCH)
                .owner("myorg")
                .build();
        var pushContext = new PushContext();
        pushContext.setRepoSlug("/myorg/myrepo");
        var registry = new InMemoryUrlRuleRegistry();
        registry.save(fetchOnlyAllow);
        var hook = new RepositoryUrlRuleHook(registry, GITHUB, null, pushContext);
        var cmd = makeCmd();

        hook.onPreReceive(new ReceivePack(repo), List.of(cmd));

        // FETCH-only allow rule does not engage for push — no push rule matched → fail-closed
        assertEquals(StepStatus.FAIL, pushContext.getSteps().get(0).getStatus());
    }

    @Test
    void fetchOnlyDenyRule_doesNotBlockPush() throws Exception {

        var fetchDeny = AccessRule.builder()
                .ruleOrder(100)
                .access(AccessRule.Access.DENY)
                .operations(AccessRule.Operations.FETCH)
                .owner("myorg")
                .build();
        var pushAllow = AccessRule.builder()
                .ruleOrder(200)
                .access(AccessRule.Access.ALLOW)
                .operations(AccessRule.Operations.BOTH)
                .owner("myorg")
                .build();
        var pushContext = new PushContext();
        pushContext.setRepoSlug("/myorg/myrepo");
        var registry = new InMemoryUrlRuleRegistry();
        registry.save(fetchDeny);
        registry.save(pushAllow);
        var hook = new RepositoryUrlRuleHook(registry, GITHUB, null, pushContext);
        var cmd = makeCmd();

        hook.onPreReceive(new ReceivePack(repo), List.of(cmd));

        assertEquals(StepStatus.PASS, pushContext.getSteps().get(0).getStatus());
        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, cmd.getResult());
    }
}
