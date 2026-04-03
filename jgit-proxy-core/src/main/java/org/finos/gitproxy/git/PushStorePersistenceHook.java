package org.finos.gitproxy.git;

import static org.finos.gitproxy.git.GitClientUtils.AnsiColor.*;
import static org.finos.gitproxy.git.GitClientUtils.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClientUtils.color;
import static org.finos.gitproxy.git.GitClientUtils.sym;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.*;
import org.finos.gitproxy.db.PushRecordMapper;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.model.PushCommit;
import org.finos.gitproxy.db.model.PushRecord;
import org.finos.gitproxy.db.model.PushStatus;
import org.finos.gitproxy.db.model.PushStep;
import org.finos.gitproxy.db.model.StepStatus;
import org.finos.gitproxy.provider.GitProxyProvider;

/**
 * Combined pre-receive and post-receive hook that persists push records to the {@link PushStore} during
 * store-and-forward processing.
 *
 * <p>The pre-receive hook creates the initial record with status RECEIVED. It should be placed at the beginning of the
 * hook chain. It also stores the push ID in the ReceivePack so downstream hooks and the post-receive hook can update
 * it.
 *
 * <p>The post-receive hook updates the record based on the final command results (FORWARDED, BLOCKED, or ERROR).
 */
@Slf4j
public class PushStorePersistenceHook {

    /** ReceivePack message key used to pass the push ID between pre/post hooks. */
    private static final String PUSH_ID_KEY = "gitproxy.pushId";

    /**
     * Maps S&F hook names to canonical step orders matching the equivalent proxy filter. Used so REJECTED push records
     * sort validation steps in the same order as proxy mode.
     */
    private static final Map<String, Integer> HOOK_STEP_ORDER = Map.of(
            "checkAuthorEmails", 2100,
            "checkCommitMessages", 2200,
            "scanDiff", 2300,
            "scanSecrets", 2500);

    private final PushStore pushStore;
    private final GitProxyProvider provider;
    private PushContext pushContext;
    private String serviceUrl;

    public PushStorePersistenceHook(PushStore pushStore, GitProxyProvider provider) {
        this.pushStore = pushStore;
        this.provider = provider;
    }

    /** Set the shared push context for accumulating steps from other hooks (e.g., diff generation). */
    public void setPushContext(PushContext pushContext) {
        this.pushContext = pushContext;
    }

    /** Set the dashboard service URL so the rejection message can include a direct link to the push record. */
    public void setServiceUrl(String serviceUrl) {
        this.serviceUrl = serviceUrl;
    }

    /** Returns a {@link PreReceiveHook} that creates the initial push record. Should be the first hook in the chain. */
    public PreReceiveHook preReceiveHook() {
        return (ReceivePack rp, Collection<ReceiveCommand> commands) -> {
            String pushId = UUID.randomUUID().toString();
            // Store push ID in repo config so post-receive can find it
            rp.getRepository().getConfig().setString("gitproxy", null, "pushId", pushId);

            try {
                PushRecord record = buildInitialRecord(pushId, rp, commands);
                pushStore.save(record);
                log.info("Created push record: id={}, repo={}", pushId, record.getUrl());
            } catch (Exception e) {
                log.error("Failed to create push record", e);
            }
        };
    }

    /**
     * Returns a {@link PreReceiveHook} that captures the validation results after all validation hooks have run. Should
     * be placed after all validation hooks but before the forwarding post-receive hook.
     *
     * <p>Creates a new event-log record for the validation outcome, linked to the original push via the same upstream
     * URL and commit range.
     */
    public PreReceiveHook validationResultHook(ValidationContext validationContext) {
        return (ReceivePack rp, Collection<ReceiveCommand> commands) -> {
            String pushId = rp.getRepository().getConfig().getString("gitproxy", null, "pushId");
            if (pushId == null) return;

            try {
                pushStore.findById(pushId).ifPresent(initial -> {
                    PushRecord record = copyBase(initial);

                    // Collect all steps: validation issues + push context (diffs, etc.)
                    List<PushStep> steps = new ArrayList<>();
                    String recordId = record.getId();

                    // Validation issues → reject outright (no human review queue)
                    if (validationContext.hasIssues()) {
                        // Build merged step list: passing steps first, then failing steps
                        List<PushStep> allSteps = new ArrayList<>();
                        if (pushContext != null) {
                            for (PushStep step : pushContext.getSteps()) {
                                step.setPushId(recordId);
                                allSteps.add(step);
                            }
                        }
                        int fallbackOrder = 0;
                        for (var issue : validationContext.getIssues()) {
                            int stepOrder = HOOK_STEP_ORDER.getOrDefault(issue.hookName(), fallbackOrder);
                            allSteps.add(PushStep.builder()
                                    .pushId(recordId)
                                    .stepName(issue.hookName())
                                    .stepOrder(stepOrder)
                                    .status(StepStatus.FAIL)
                                    .content(issue.detail())
                                    .errorMessage(issue.summary())
                                    .build());
                            fallbackOrder++;
                        }

                        allSteps.sort(Comparator.comparingInt(PushStep::getStepOrder));

                        record.setStatus(PushStatus.REJECTED);
                        record.setAutoRejected(true);
                        record.setBlockedMessage(validationContext.getIssues().size() + " validation issue(s) found");
                        record.setSteps(allSteps);
                        pushStore.save(record);
                        rp.getRepository()
                                .getConfig()
                                .setString("gitproxy", null, "validationRecordId", record.getId());
                        log.debug(
                                "Saved validation result record: id={}, status=REJECTED (auto-rejected)",
                                record.getId());

                        // Emit validation summary (passing steps + failing steps, sorted by order)
                        String summary = GitClientUtils.buildValidationSummary(allSteps);
                        if (!summary.isBlank()) {
                            rp.sendMessage(summary);
                        }
                        // Compact rejection block with actionable detail per violation
                        rp.sendMessage("────────────────────────────────────────");
                        rp.sendMessage(color(
                                RED,
                                "" + sym(NO_ENTRY) + "  Push Blocked — "
                                        + validationContext.getIssues().size() + " validation issue(s)"));
                        for (var issue : validationContext.getIssues()) {
                            rp.sendMessage("  " + issue.detail());
                        }
                        rp.sendMessage("────────────────────────────────────────");

                        if (serviceUrl != null) {
                            rp.sendMessage(color(
                                    CYAN,
                                    "" + sym(LINK) + "  View push record: " + serviceUrl + "/#/push/"
                                            + record.getId()));
                        }

                        // Reject all commands immediately — no approval wait
                        String rejectMsg = validationContext.getIssues().size() + " validation issue(s) — see above";
                        for (ReceiveCommand cmd : commands) {
                            if (cmd.getResult() == ReceiveCommand.Result.NOT_ATTEMPTED) {
                                cmd.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, rejectMsg);
                            }
                        }
                        return;
                    }

                    // No validation issues → BLOCKED, pending human review
                    record.setStatus(PushStatus.BLOCKED);

                    // Steps from push context (diffs, scans, etc.)
                    if (pushContext != null) {
                        for (PushStep step : pushContext.getSteps()) {
                            step.setPushId(recordId);
                            steps.add(step);
                        }
                    }
                    steps.sort(Comparator.comparingInt(PushStep::getStepOrder));
                    record.setSteps(steps);

                    // Show validation summary before the approval wait message
                    String summary = GitClientUtils.buildValidationSummary(steps);
                    if (!summary.isBlank()) {
                        rp.sendMessage(summary);
                    }
                    rp.sendMessage("────────────────────────────────────────");
                    if (serviceUrl != null) {
                        rp.sendMessage(color(
                                CYAN,
                                "" + sym(LINK) + "  View push record: " + serviceUrl + "/#/push/" + record.getId()));
                    }

                    pushStore.save(record);
                    rp.getRepository().getConfig().setString("gitproxy", null, "validationRecordId", record.getId());
                    log.debug(
                            "Saved validation result record: id={}, status=BLOCKED (awaiting review)", record.getId());
                });
            } catch (Exception e) {
                log.error("Failed to save validation result record", e);
            }
        };
    }

    /**
     * Returns a {@link PostReceiveHook} that records the forwarding outcome. Should be placed after the forwarding
     * hook.
     *
     * <p>Creates a new event-log record for the forwarding result.
     */
    public PostReceiveHook postReceiveHook() {
        return (ReceivePack rp, Collection<ReceiveCommand> commands) -> {
            String pushId = rp.getRepository().getConfig().getString("gitproxy", null, "pushId");
            if (pushId == null) return;

            try {
                pushStore.findById(pushId).ifPresent(initial -> {
                    // JGit only passes Result.OK commands to post-receive.
                    // An empty list means all commands were rejected by pre-receive — nothing to record.
                    if (commands.isEmpty()) {
                        log.debug("Skipping post-receive record: no OK commands (push was rejected)");
                        return;
                    }

                    boolean allOk = commands.stream().allMatch(cmd -> cmd.getResult() == ReceiveCommand.Result.OK);
                    boolean anyTransportFailed = commands.stream()
                            .anyMatch(cmd -> cmd.getResult() != ReceiveCommand.Result.OK
                                    && cmd.getResult() != ReceiveCommand.Result.NOT_ATTEMPTED);

                    PushRecord record = copyBase(initial);
                    if (allOk) {
                        record.setStatus(PushStatus.FORWARDED);
                    } else if (anyTransportFailed) {
                        record.setStatus(PushStatus.ERROR);
                        commands.stream()
                                .filter(cmd -> cmd.getResult() != ReceiveCommand.Result.OK
                                        && cmd.getResult() != ReceiveCommand.Result.NOT_ATTEMPTED)
                                .findFirst()
                                .ifPresent(cmd -> record.setErrorMessage(
                                        cmd.getRefName() + ": " + cmd.getResult() + " - " + cmd.getMessage()));
                    }

                    pushStore.save(record);
                    log.info("Saved forwarding result record: id={}, status={}", record.getId(), record.getStatus());
                });
            } catch (Exception e) {
                log.error("Failed to save forwarding result record", e);
            }
        };
    }

    /**
     * Create a new record that copies the base fields (repo, branch, commits, author) from an existing record but with
     * a fresh ID and timestamp. Used for event-log style persistence where each state transition is a separate row.
     */
    private PushRecord copyBase(PushRecord source) {
        return PushRecord.builder()
                .url(source.getUrl())
                .upstreamUrl(source.getUpstreamUrl())
                .project(source.getProject())
                .repoName(source.getRepoName())
                .branch(source.getBranch())
                .commitFrom(source.getCommitFrom())
                .commitTo(source.getCommitTo())
                .message(source.getMessage())
                .author(source.getAuthor())
                .authorEmail(source.getAuthorEmail())
                .committer(source.getCommitter())
                .committerEmail(source.getCommitterEmail())
                .user(source.getUser())
                .userEmail(source.getUserEmail())
                .method(source.getMethod())
                .commits(source.getCommits())
                .build();
    }

    private PushRecord buildInitialRecord(String pushId, ReceivePack rp, Collection<ReceiveCommand> commands) {
        String providerUri = provider.getUri().toString();
        Repository repo = rp.getRepository();
        PushRecord.PushRecordBuilder builder = PushRecord.builder()
                .id(pushId)
                .status(PushStatus.RECEIVED)
                .url(providerUri)
                .project(provider.getUri().getHost());

        // Extract push user from repo config (set by StoreAndForwardReceivePackFactory)
        String pushUser = repo.getConfig().getString("gitproxy", null, "pushUser");
        if (pushUser != null) {
            builder.user(pushUser);
        }

        // Extract upstream URL and repo name from repo config (set by StoreAndForwardRepositoryResolver)
        String upstreamUrl = repo.getConfig().getString("gitproxy", null, "upstreamUrl");
        if (upstreamUrl != null) {
            builder.upstreamUrl(upstreamUrl);
            // Parse repo name from upstream URL (e.g., "https://github.com/owner/repo.git" -> "repo")
            String path = upstreamUrl.replaceAll("\\.git$", "");
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash >= 0) {
                builder.repoName(path.substring(lastSlash + 1));
                // Try to extract owner/slug
                String withoutScheme = path.replaceFirst("https?://[^/]+/", "");
                if (withoutScheme.contains("/")) {
                    builder.project(withoutScheme.substring(0, withoutScheme.indexOf('/')));
                }
            }
        }

        // Extract ref info from the first command
        commands.stream().findFirst().ifPresent(cmd -> {
            builder.branch(cmd.getRefName());
            builder.commitFrom(cmd.getOldId().name());
            builder.commitTo(cmd.getNewId().name());
        });

        // Try to extract commit details from the repository
        List<PushCommit> commits = new ArrayList<>();
        for (ReceiveCommand cmd : commands) {
            if (cmd.getType() == ReceiveCommand.Type.DELETE) continue;
            try {
                String toCommit = cmd.getNewId().name();
                if (ObjectId.zeroId().equals(cmd.getOldId())) {
                    // New branch - just get tip commit
                    Commit tip = CommitInspectionService.getCommitDetails(repo, toCommit);
                    commits.add(PushRecordMapper.mapCommit(pushId, tip));
                    if (tip.getAuthor() != null) {
                        builder.author(tip.getAuthor().getName());
                        builder.authorEmail(tip.getAuthor().getEmail());
                    }
                    if (tip.getCommitter() != null) {
                        builder.committer(tip.getCommitter().getName());
                        builder.committerEmail(tip.getCommitter().getEmail());
                    }
                    if (tip.getMessage() != null) {
                        builder.message(tip.getMessage().lines().findFirst().orElse(null));
                    }
                } else {
                    List<Commit> range = CommitInspectionService.getCommitRange(
                            repo, cmd.getOldId().name(), toCommit);
                    for (Commit c : range) {
                        commits.add(PushRecordMapper.mapCommit(pushId, c));
                    }
                    // Use the latest commit's author, committer, and headline message
                    if (!range.isEmpty()) {
                        Commit head = range.get(0);
                        if (head.getAuthor() != null) {
                            builder.author(head.getAuthor().getName());
                            builder.authorEmail(head.getAuthor().getEmail());
                        }
                        if (head.getCommitter() != null) {
                            builder.committer(head.getCommitter().getName());
                            builder.committerEmail(head.getCommitter().getEmail());
                        }
                        if (head.getMessage() != null) {
                            builder.message(
                                    head.getMessage().lines().findFirst().orElse(null));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to extract commit details for {}", cmd.getRefName(), e);
            }
        }
        builder.commits(commits);

        return builder.build();
    }
}
