package org.finos.gitproxy.git;

import static org.finos.gitproxy.git.GitClientUtils.AnsiColor.*;
import static org.finos.gitproxy.git.GitClientUtils.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClientUtils.sym;

import java.util.Collection;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.finos.gitproxy.db.RepoRegistry;
import org.finos.gitproxy.db.model.PushStep;
import org.finos.gitproxy.db.model.StepStatus;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.servlet.filter.UrlRuleEvaluator;
import org.finos.gitproxy.servlet.filter.UrlRuleFilter;

/**
 * Pre-receive hook that enforces URL allow/deny rules in store-and-forward mode. Rule evaluation is delegated entirely
 * to {@link UrlRuleEvaluator}; this class only handles extracting the JGit context and writing JGit responses.
 *
 * <p>Mirrors the behaviour of {@link org.finos.gitproxy.servlet.filter.UrlRuleAggregateFilter} for the JGit hook chain.
 */
@Slf4j
public class RepositoryUrlRuleHook implements GitProxyHook {

    private static final int ORDER = 100;

    private final UrlRuleEvaluator evaluator;
    private final ValidationContext validationContext;
    private final PushContext pushContext;

    /** Open-mode constructor — no rules configured; always passes. Used in tests and simple setups. */
    public RepositoryUrlRuleHook(PushContext pushContext) {
        this(List.of(), null, null, null, pushContext);
    }

    public RepositoryUrlRuleHook(
            List<UrlRuleFilter> urlRuleFilters,
            RepoRegistry repoRegistry,
            GitProxyProvider provider,
            ValidationContext validationContext,
            PushContext pushContext) {
        this.evaluator = new UrlRuleEvaluator(urlRuleFilters, repoRegistry, provider);
        this.validationContext = validationContext;
        this.pushContext = pushContext;
    }

    @Override
    public void onPreReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
        // Open mode: evaluator has no config rules and no registry
        // (detected inside evaluator — returns OpenMode)

        String repoSlug = rp.getRepository().getConfig().getString("gitproxy", null, "repoSlug");
        if (repoSlug == null || repoSlug.isBlank()) {
            // No repoSlug means we can't evaluate rules — fail closed
            // Exception: if the evaluator is in pure open mode (no rules at all), allow it
            UrlRuleEvaluator.Result probe = evaluator.evaluate(null, null, null, HttpOperation.PUSH);
            if (probe instanceof UrlRuleEvaluator.Result.OpenMode) {
                log.debug("No repoSlug and no rules configured — allowing push (open mode)");
                recordPass();
                return;
            }
            log.warn("No repoSlug in repo config — cannot evaluate URL rules, blocking push (fail-closed)");
            blockPush(rp, commands, "Repository path unavailable");
            return;
        }

        // Parse owner and name from /owner/name slug
        String[] parts = repoSlug.split("/", 4);
        String owner = parts.length >= 2 ? parts[1] : null;
        String name = parts.length >= 3 ? parts[2] : null;
        String normSlug = (owner != null && name != null) ? owner + "/" + name : strip(repoSlug);

        UrlRuleEvaluator.Result result = evaluator.evaluate(normSlug, owner, name, HttpOperation.PUSH);

        switch (result) {
            case UrlRuleEvaluator.Result.Denied d -> {
                log.debug("Push blocked by deny rule: {}", d.ruleId());
                blockPush(rp, commands, "Repository denied by access rule");
            }
            case UrlRuleEvaluator.Result.Allowed a -> {
                log.debug("Push allowed by rule: {}", a.ruleId());
                recordPass();
            }
            case UrlRuleEvaluator.Result.OpenMode m -> {
                log.debug("Push allowed — open mode (no allow rules configured)");
                recordPass();
            }
            case UrlRuleEvaluator.Result.NotAllowed n -> {
                log.debug("Push blocked — no allow rule matched");
                blockPush(rp, commands, "Repository is not in the allow list");
            }
        }
    }

    private void blockPush(ReceivePack rp, Collection<ReceiveCommand> commands, String reason) {
        String detail = GitClientUtils.format(
                sym(NO_ENTRY) + "  Push Blocked - Repository Not Allowed",
                sym(CROSS_MARK)
                        + "  "
                        + reason
                        + ".\n\nContact an administrator to add this repository to the allow rules.",
                RED,
                null);
        if (validationContext != null) {
            validationContext.addIssue("RepositoryUrlRuleHook", reason, detail);
        } else {
            rp.sendMessage(detail);
            for (ReceiveCommand cmd : commands) {
                if (cmd.getResult() == ReceiveCommand.Result.NOT_ATTEMPTED) {
                    cmd.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, reason);
                }
            }
        }
        pushContext.addStep(PushStep.builder()
                .stepName("checkUrlRules")
                .stepOrder(ORDER)
                .status(StepStatus.FAIL)
                .content(reason)
                .build());
    }

    private void recordPass() {
        pushContext.addStep(PushStep.builder()
                .stepName("checkUrlRules")
                .stepOrder(ORDER)
                .status(StepStatus.PASS)
                .build());
    }

    private static String strip(String s) {
        return (s != null && s.startsWith("/")) ? s.substring(1) : s;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public String getName() {
        return "RepositoryUrlRuleHook";
    }
}
