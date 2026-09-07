package com.rbc.fogwall.git;

import static org.junit.jupiter.api.Assertions.*;

import com.rbc.fogwall.db.memory.InMemoryUrlRuleRegistry;
import com.rbc.fogwall.db.model.AccessRule;
import com.rbc.fogwall.db.model.MatchTarget;
import com.rbc.fogwall.db.model.MatchType;
import com.rbc.fogwall.db.model.StepStatus;
import com.rbc.fogwall.provider.FogwallProvider;
import com.rbc.fogwall.provider.GitHubProvider;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryUrlRuleHookTest {

    private static final FogwallProvider GITHUB = new GitHubProvider("/proxy");

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

    private static AccessRule ownerRule(AccessRule.Access access, AccessRule.Operation ops, String owner, int order) {
        return AccessRule.builder()
                .ruleOrder(order)
                .access(access)
                .operation(ops)
                .target(MatchTarget.OWNER)
                .value(owner)
                .matchType(MatchType.GLOB)
                .build();
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

        var allowRule = ownerRule(AccessRule.Access.ALLOW, AccessRule.Operation.BOTH, "myorg", 100);
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

        var allowRule = ownerRule(AccessRule.Access.ALLOW, AccessRule.Operation.BOTH, "other-org", 100);
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

        var denyRule = ownerRule(AccessRule.Access.DENY, AccessRule.Operation.BOTH, "myorg", 100);
        var allowRule = ownerRule(AccessRule.Access.ALLOW, AccessRule.Operation.BOTH, "myorg", 200);
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

        var fetchOnlyAllow = ownerRule(AccessRule.Access.ALLOW, AccessRule.Operation.FETCH, "myorg", 100);
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

        var fetchDeny = ownerRule(AccessRule.Access.DENY, AccessRule.Operation.FETCH, "myorg", 100);
        var pushAllow = ownerRule(AccessRule.Access.ALLOW, AccessRule.Operation.BOTH, "myorg", 200);
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

    @Test
    void slugRegexRuleWithLeadingSlash_matches() throws Exception {

        var regexAllow = AccessRule.builder()
                .ruleOrder(100)
                .access(AccessRule.Access.ALLOW)
                .operation(AccessRule.Operation.BOTH)
                .target(MatchTarget.SLUG)
                .value("/myorg/myrepo(-2)?")
                .matchType(MatchType.REGEX)
                .build();
        var pushContext = new PushContext();
        pushContext.setRepoSlug("/myorg/myrepo");
        var registry = new InMemoryUrlRuleRegistry();
        registry.save(regexAllow);
        var hook = new RepositoryUrlRuleHook(registry, GITHUB, null, pushContext);
        var cmd = makeCmd();

        hook.onPreReceive(new ReceivePack(repo), List.of(cmd));

        // REGEX receives the value as-is, so the slug must keep the leading '/' the proxy mode also passes
        assertEquals(StepStatus.PASS, pushContext.getSteps().get(0).getStatus());
        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, cmd.getResult());
    }

    // ── Nested group paths (GitLab subgroups) ────────────────────────────────

    private static AccessRule slugRule(AccessRule.Access access, String slug, int order) {
        return AccessRule.builder()
                .ruleOrder(order)
                .access(access)
                .operation(AccessRule.Operation.BOTH)
                .target(MatchTarget.SLUG)
                .value(slug)
                .matchType(MatchType.LITERAL)
                .build();
    }

    @Test
    void nestedGroupPath_denySlugRuleMatches_rejectsCommand() throws Exception {

        var pushContext = new PushContext();
        pushContext.setRepoSlug("/group/subgroup/restricted");
        var registry = new InMemoryUrlRuleRegistry();
        registry.save(slugRule(AccessRule.Access.DENY, "/group/subgroup/restricted", 100));
        registry.save(slugRule(AccessRule.Access.ALLOW, "/group/subgroup/restricted", 200));
        var hook = new RepositoryUrlRuleHook(registry, GITHUB, null, pushContext);
        var cmd = makeCmd();

        hook.onPreReceive(new ReceivePack(repo), List.of(cmd));

        assertEquals(StepStatus.FAIL, pushContext.getSteps().get(0).getStatus());
        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, cmd.getResult());
    }

    @Test
    void nestedGroupPath_allowRuleNamingSubgroupAlone_doesNotAdmitItsProjects() throws Exception {

        var pushContext = new PushContext();
        pushContext.setRepoSlug("/group/subgroup/project");
        var registry = new InMemoryUrlRuleRegistry();
        registry.save(slugRule(AccessRule.Access.ALLOW, "/group/subgroup", 100));
        var hook = new RepositoryUrlRuleHook(registry, GITHUB, null, pushContext);
        var cmd = makeCmd();

        hook.onPreReceive(new ReceivePack(repo), List.of(cmd));

        assertEquals(StepStatus.FAIL, pushContext.getSteps().get(0).getStatus());
        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, cmd.getResult());
    }

    @Test
    void nestedGroupPath_ownerRuleMatchesWholeNamespace_recordsPass() throws Exception {

        var pushContext = new PushContext();
        pushContext.setRepoSlug("/group/subgroup/project");
        var registry = new InMemoryUrlRuleRegistry();
        registry.save(ownerRule(AccessRule.Access.ALLOW, AccessRule.Operation.BOTH, "group/subgroup", 100));
        var hook = new RepositoryUrlRuleHook(registry, GITHUB, null, pushContext);
        var cmd = makeCmd();

        hook.onPreReceive(new ReceivePack(repo), List.of(cmd));

        assertEquals(StepStatus.PASS, pushContext.getSteps().get(0).getStatus());
        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, cmd.getResult());
    }
}
