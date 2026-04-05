package org.finos.gitproxy.git;

import static org.finos.gitproxy.git.GitClientUtils.AnsiColor.*;
import static org.finos.gitproxy.git.GitClientUtils.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClientUtils.color;
import static org.finos.gitproxy.git.GitClientUtils.sym;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.*;
import org.finos.gitproxy.db.model.PushStep;
import org.finos.gitproxy.db.model.StepStatus;
import org.finos.gitproxy.provider.GitProxyProvider;

/**
 * Post-receive hook that forwards successfully received refs to the upstream provider. This is the "forward" part of
 * store-and-forward: after objects are stored locally and refs updated, we push them upstream.
 *
 * <p>If the upstream push fails, the objects remain stored locally. A future retry mechanism could re-attempt the
 * forwarding.
 */
@Slf4j
@RequiredArgsConstructor
public class ForwardingPostReceiveHook implements PostReceiveHook {

    private final GitProxyProvider provider;
    private final CredentialsProvider credentials;
    private final PushContext pushContext;

    @Override
    public void onPostReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
        List<ReceiveCommand> accepted = commands.stream()
                .filter(cmd -> cmd.getResult() == ReceiveCommand.Result.OK)
                .toList();

        if (accepted.isEmpty()) {
            log.debug("No refs to forward - all commands rejected in pre-receive");
            pushContext.addStep(PushStep.builder()
                    .stepName("forward")
                    .status(StepStatus.PASS)
                    .logs(List.of("No refs to forward"))
                    .build());
            return;
        }

        Repository repo = rp.getRepository();

        // If BitbucketCredentialRewriteHook resolved an upstream username, use it instead of the push email.
        String upstreamUser = repo.getConfig().getString("gitproxy", null, "upstreamUser");
        CredentialsProvider effectiveCreds = credentials;
        if (upstreamUser != null) {
            String pushToken = repo.getConfig().getString("gitproxy", null, "pushToken");
            effectiveCreds = new UsernamePasswordCredentialsProvider(upstreamUser, pushToken != null ? pushToken : "");
            log.debug("Using Bitbucket upstream username '{}' for forwarding credentials", upstreamUser);
        }

        String upstreamUrl = repo.getConfig().getString("gitproxy", null, "upstreamUrl");

        if (upstreamUrl == null) {
            rp.sendMessage(color(RED, sym(NO_ENTRY) + "  ERROR - no upstream URL configured, cannot forward"));
            log.error("No gitproxy.upstreamUrl in repo config for {}", repo.getDirectory());
            pushContext.addStep(PushStep.builder()
                    .stepName("forward")
                    .status(StepStatus.FAIL)
                    .errorMessage("No upstream URL configured")
                    .logs(List.of("ERROR: no gitproxy.upstreamUrl in repo config"))
                    .build());
            return;
        }

        rp.sendMessage(color(CYAN, sym(LINK) + "  Forwarding to " + upstreamUrl + "..."));

        List<String> logs = new ArrayList<>();
        logs.add("Forwarding to " + upstreamUrl);
        boolean forwardFailed = false;
        String forwardError = null;

        try {
            URIish upstream = new URIish(upstreamUrl);
            forwardFailed = pushToUpstream(rp, repo, upstream, accepted, logs, effectiveCreds);
        } catch (Exception e) {
            rp.sendMessage(color(RED, sym(CROSS_MARK) + "  ERROR forwarding to upstream: " + e.getMessage()));
            log.error("Failed to push to upstream {}", upstreamUrl, e);
            logs.add("ERROR: " + e.getMessage());
            forwardFailed = true;
            forwardError = e.getMessage();
        }

        pushContext.addStep(PushStep.builder()
                .stepName("forward")
                .status(forwardFailed ? StepStatus.FAIL : StepStatus.PASS)
                .errorMessage(forwardError)
                .logs(logs)
                .build());
    }

    /** Returns true if any ref failed to forward. */
    private boolean pushToUpstream(
            ReceivePack rp,
            Repository repo,
            URIish upstream,
            List<ReceiveCommand> commands,
            List<String> logs,
            CredentialsProvider creds)
            throws Exception {

        boolean anyFailed = false;
        try (Transport transport = Transport.open(repo, upstream)) {
            if (creds != null) {
                transport.setCredentialsProvider(creds);
            }

            List<RemoteRefUpdate> updates = buildRefUpdates(repo, commands);

            rp.sendMessage(color(CYAN, "  Pushing " + updates.size() + " ref(s) to upstream..."));

            PushResult result = transport.push(NullProgressMonitor.INSTANCE, updates);

            for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                RemoteRefUpdate.Status status = update.getStatus();
                String remoteName = update.getRemoteName();

                switch (status) {
                    case OK:
                    case UP_TO_DATE:
                        rp.sendMessage(
                                color(GREEN, "  " + sym(HEAVY_CHECK_MARK) + "  " + remoteName + " -> " + status));
                        logs.add("PASS: " + remoteName + " -> " + status);
                        break;
                    default:
                        String message = update.getMessage();
                        rp.sendMessage(color(
                                RED,
                                "  " + sym(CROSS_MARK) + "  " + remoteName + " -> " + status
                                        + (message != null ? " (" + message + ")" : "")));
                        log.warn("Upstream push ref {} status: {} {}", remoteName, status, message);
                        logs.add("FAIL: " + remoteName + " -> " + status
                                + (message != null ? " (" + message + ")" : ""));
                        anyFailed = true;
                }
            }

            rp.sendMessage(color(GREEN, sym(HEAVY_CHECK_MARK) + "  Forwarding complete"));
        }
        return anyFailed;
    }

    private List<RemoteRefUpdate> buildRefUpdates(Repository repo, List<ReceiveCommand> commands) throws Exception {
        List<RemoteRefUpdate> updates = new ArrayList<>();

        for (ReceiveCommand cmd : commands) {
            String refName = cmd.getRefName();

            switch (cmd.getType()) {
                case DELETE:
                    updates.add(new RemoteRefUpdate(
                            repo,
                            (String) null, // no source ref
                            refName, // destination ref to delete
                            true, // force delete
                            null, // no tracking ref
                            cmd.getOldId()));
                    break;

                case CREATE:
                case UPDATE:
                case UPDATE_NONFASTFORWARD:
                    boolean force = cmd.getType() == ReceiveCommand.Type.UPDATE_NONFASTFORWARD;
                    updates.add(new RemoteRefUpdate(
                            repo,
                            refName, // source ref
                            refName, // destination ref (same name)
                            force,
                            null, // no tracking ref
                            cmd.getOldId()));
                    break;
            }
        }

        return updates;
    }
}
