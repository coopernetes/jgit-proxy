package com.rbc.fogwall.git;

import static com.rbc.fogwall.git.GitClientUtils.AnsiColor.*;
import static com.rbc.fogwall.git.GitClientUtils.SymbolCodes.*;
import static com.rbc.fogwall.git.GitClientUtils.sym;

import com.rbc.fogwall.db.UrlRuleRegistry;
import com.rbc.fogwall.db.model.PushStep;
import com.rbc.fogwall.db.model.StepStatus;
import com.rbc.fogwall.provider.FogwallProvider;
import com.rbc.fogwall.servlet.filter.UrlRuleAggregateFilter;
import com.rbc.fogwall.servlet.filter.UrlRuleEvaluator;
import java.util.Collection;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;

/**
 * Pre-receive hook that enforces URL allow/deny rules in server mode. Rule evaluation is delegated entirely to
 * {@link UrlRuleEvaluator}; this class only handles extracting the JGit context and writing JGit responses.
 *
 * <p>Mirrors the behaviour of {@link UrlRuleAggregateFilter} for the JGit hook chain.
 */
@Slf4j
public class RepositoryUrlRuleHook implements FogwallHook {

    private static final int ORDER = 100;

    private final UrlRuleEvaluator evaluator;
    private final ValidationContext validationContext;
    private final PushContext pushContext;

    public RepositoryUrlRuleHook(
            UrlRuleRegistry urlRuleRegistry,
            FogwallProvider provider,
            ValidationContext validationContext,
            PushContext pushContext) {
        this.evaluator = new UrlRuleEvaluator(urlRuleRegistry, provider);
        this.validationContext = validationContext;
        this.pushContext = pushContext;
    }

    @Override
    public void onPreReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
        // A path that does not parse is blocked rather than evaluated against a partial reading of it: rules are
        // the containment mechanism, and a truncated slug would be matched against the wrong repository.
        Optional<RepoPath> repoPath = RepoPath.parse(pushContext.getRepoSlug());
        if (repoPath.isEmpty()) {
            log.warn(
                    "No usable repoSlug in push context ({}) — cannot evaluate URL rules, blocking push (fail-closed)",
                    pushContext.getRepoSlug());
            blockPush(
                    rp,
                    commands,
                    "Repository path unavailable",
                    "Push Blocked - Repository Not Allowed",
                    "Repository path could not be determined. Contact an administrator.");
            return;
        }

        // The slug keeps its leading '/', the form the transparent proxy passes. LITERAL and GLOB normalise it either
        // way, but REGEX receives the value raw — stripping it here made a regex rule match in one mode and not the
        // other.
        UrlRuleEvaluator.Result result = evaluator.evaluate(
                repoPath.get().slug(), repoPath.get().owner(), repoPath.get().name(), HttpOperation.PUSH);

        switch (result) {
            case UrlRuleEvaluator.Result.Denied d -> {
                log.debug("Push blocked by deny rule: {}", d.ruleId());
                blockPush(
                        rp,
                        commands,
                        "Repository blocked by deny rule",
                        "Push Blocked - Repository Denied",
                        "Pushes to this repository are not permitted.\n\n"
                                + "This repository has been explicitly denied by an administrator.");
            }
            case UrlRuleEvaluator.Result.Allowed a -> {
                log.debug("Push allowed by rule: {}", a.ruleId());
                recordPass();
            }
            case UrlRuleEvaluator.Result.NotAllowed _ -> {
                log.debug("Push blocked — no rule matched");
                blockPush(
                        rp,
                        commands,
                        "Repository not in allow list",
                        "Push Blocked - Repository Not Allowed",
                        "Pushes to this repository are not permitted.\n\n"
                                + "Contact an administrator to add this repository to the allow rules.");
            }
        }
    }

    private void blockPush(
            ReceivePack rp, Collection<ReceiveCommand> commands, String reason, String title, String message) {
        String detail =
                GitClientUtils.format(sym(NO_ENTRY) + "  " + title, sym(CROSS_MARK) + "  " + message, RED, null);
        if (validationContext != null) {
            validationContext.addIssue("checkUrlRules", reason, detail);
            // PushStorePersistenceHook creates the FAIL step from the issue; don't also add it to pushContext
        } else {
            rp.sendMessage(detail);
            for (ReceiveCommand cmd : commands) {
                if (cmd.getResult() == ReceiveCommand.Result.NOT_ATTEMPTED) {
                    cmd.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, reason);
                }
            }
            pushContext.addStep(PushStep.builder()
                    .stepName("checkUrlRules")
                    .stepOrder(ORDER)
                    .status(StepStatus.FAIL)
                    .content(reason)
                    .build());
        }
    }

    private void recordPass() {
        pushContext.addStep(PushStep.builder()
                .stepName("checkUrlRules")
                .stepOrder(ORDER)
                .status(StepStatus.PASS)
                .build());
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
