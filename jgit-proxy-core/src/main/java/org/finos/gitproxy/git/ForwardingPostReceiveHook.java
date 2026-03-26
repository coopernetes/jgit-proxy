package org.finos.gitproxy.git;

import static org.finos.gitproxy.git.GitClient.AnsiColor.*;
import static org.finos.gitproxy.git.GitClient.SymbolCodes.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.*;
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

    @Override
    public void onPostReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
        List<ReceiveCommand> accepted = commands.stream()
                .filter(cmd -> cmd.getResult() == ReceiveCommand.Result.OK)
                .toList();

        if (accepted.isEmpty()) {
            rp.sendMessage(YELLOW + "[git-proxy] " + WARNING.emoji() + "  No refs to forward" + RESET);
            return;
        }

        Repository repo = rp.getRepository();
        String upstreamUrl = repo.getConfig().getString("gitproxy", null, "upstreamUrl");

        if (upstreamUrl == null) {
            rp.sendMessage(RED + "[git-proxy] " + NO_ENTRY.emoji()
                    + "  ERROR - no upstream URL configured, cannot forward" + RESET);
            log.error("No gitproxy.upstreamUrl in repo config for {}", repo.getDirectory());
            return;
        }

        rp.sendMessage(CYAN + "[git-proxy] " + LINK.emoji() + "  Forwarding to " + upstreamUrl + "..." + RESET);

        try {
            URIish upstream = new URIish(upstreamUrl);
            pushToUpstream(rp, repo, upstream, accepted);
        } catch (Exception e) {
            rp.sendMessage(RED + "[git-proxy] " + CROSS_MARK.emoji() + "  ERROR forwarding to upstream: "
                    + e.getMessage() + RESET);
            log.error("Failed to push to upstream {}", upstreamUrl, e);
        }
    }

    private void pushToUpstream(ReceivePack rp, Repository repo, URIish upstream, List<ReceiveCommand> commands)
            throws Exception {

        try (Transport transport = Transport.open(repo, upstream)) {
            if (credentials != null) {
                transport.setCredentialsProvider(credentials);
            }

            List<RemoteRefUpdate> updates = buildRefUpdates(repo, commands);

            rp.sendMessage(CYAN + "[git-proxy]   Pushing " + updates.size() + " ref(s) to upstream..." + RESET);

            PushResult result = transport.push(NullProgressMonitor.INSTANCE, updates);

            for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                RemoteRefUpdate.Status status = update.getStatus();
                String remoteName = update.getRemoteName();

                switch (status) {
                    case OK:
                    case UP_TO_DATE:
                        rp.sendMessage(GREEN + "[git-proxy]   " + HEAVY_CHECK_MARK.emoji() + "  " + remoteName + " -> "
                                + status + RESET);
                        break;
                    default:
                        String message = update.getMessage();
                        rp.sendMessage(RED + "[git-proxy]   " + CROSS_MARK.emoji() + "  " + remoteName + " -> " + status
                                + (message != null ? " (" + message + ")" : "") + RESET);
                        log.warn("Upstream push ref {} status: {} {}", remoteName, status, message);
                }
            }

            rp.sendMessage(GREEN + "[git-proxy] " + HEAVY_CHECK_MARK.emoji() + "  Forwarding complete" + RESET);
        }
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
