package com.rbc.fogwall.ssh;

import com.rbc.fogwall.approval.ClientLivenessCheck;
import com.rbc.fogwall.git.LocalRepositoryCache;
import com.rbc.fogwall.git.PushTransport;
import com.rbc.fogwall.git.QuarantineObjectStore;
import com.rbc.fogwall.git.RepoPath;
import com.rbc.fogwall.git.ServerReceivePackFactory;
import com.rbc.fogwall.provider.FogwallProvider;
import com.rbc.fogwall.user.UserEntry;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.agent.SshAgent;
import org.apache.sshd.common.Closeable;
import org.apache.sshd.common.channel.exception.SshChannelClosedException;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.session.ServerSession;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceivePack;

/**
 * MINA SSHD {@link Command} that handles {@code git-receive-pack} over SSH. Delegates to the same
 * {@link ServerReceivePackFactory} hook chain used by the HTTP push path.
 *
 * <p>Upstream authentication uses the client's forwarded SSH agent (requires {@code ssh -A}) via
 * {@link SshUpstreamTransport}, shared with {@link SshGitUploadCommand}.
 */
@Slf4j
public class SshGitReceiveCommand implements Command {

    private final String repoPath;
    private final Map<String, SshProviderTarget> routes;
    private final LocalRepositoryCache cache;
    private final FogwallProxyAgentFactory agentFactory;
    private final Path knownHostsFile;
    private final boolean trustOnFirstUse;

    private InputStream in;
    private OutputStream out;
    private OutputStream err;
    private ExitCallback exitCallback;

    SshGitReceiveCommand(
            String repoPath,
            Map<String, SshProviderTarget> routes,
            LocalRepositoryCache cache,
            FogwallProxyAgentFactory agentFactory,
            Path knownHostsFile,
            boolean trustOnFirstUse) {
        this.repoPath = repoPath;
        this.routes = routes;
        this.cache = cache;
        this.agentFactory = agentFactory;
        this.knownHostsFile = knownHostsFile;
        this.trustOnFirstUse = trustOnFirstUse;
    }

    @Override
    public void setInputStream(InputStream in) {
        this.in = in;
    }

    @Override
    public void setOutputStream(OutputStream out) {
        this.out = out;
    }

    @Override
    public void setErrorStream(OutputStream err) {
        this.err = err;
    }

    @Override
    public void setExitCallback(ExitCallback callback) {
        this.exitCallback = callback;
    }

    @Override
    public void start(ChannelSession channel, Environment env) {
        String sshUser = channel.getSession().getUsername();
        // Resolved during public-key auth — may be absent if the store returned empty (shouldn't happen
        // since auth would have been rejected, but guard defensively).
        var serverSession = (ServerSession) channel.getSession();
        UserEntry resolvedUser = SshGitServer.getResolvedUser(serverSession).orElse(null);
        String connectingFingerprint =
                SshGitServer.getConnectingFingerprint(serverSession).orElse(null);
        // SSH_AUTH_SOCK is set on the channel env by MINA SSHD when the client's ssh -A forwarding
        // channel is established. Capture it here before handing off to the worker thread.
        String authSocket = env.getEnv().get(SshAgent.SSH_AUTHSOCKET_ENV_NAME);
        // The session's own close state is maintained by MINA's read loop independent of this worker
        // thread, so it reflects a client disconnect even while the worker sits in the approval-wait poll.
        ClientLivenessCheck liveness = serverSession instanceof Closeable closeable
                ? () -> !closeable.isClosing()
                : ClientLivenessCheck.alwaysConnected();
        // Virtual thread: the worker can park for the full approval wait without holding a platform thread.
        Thread.ofVirtual()
                .name("ssh-git-receive-" + repoPath)
                .start(() -> runReceivePack(sshUser, resolvedUser, connectingFingerprint, authSocket, liveness));
    }

    /**
     * Parses an SSH git path and resolves which configured provider it targets.
     *
     * <p>Path format: {@code /{providerPath}/{owner}/{repo}.git}, where {@code providerPath} is a provider's
     * {@link FogwallProvider#servletPath()} (typically {@code {host}} or {@code {host}:{port}}, or a configured
     * {@code pathSuffix}) — mirrors the HTTP {@code /push/{providerPath}/{owner}/{repo}.git} convention. Matching is by
     * longest-prefix so a multi-segment {@code pathSuffix} still resolves correctly.
     *
     * @return a {@link RepoRoute} if the path is valid and matches a configured provider
     * @throws IllegalArgumentException if the path is malformed or doesn't match any configured provider
     */
    static RepoRoute resolveRoute(String repoPath, Map<String, SshProviderTarget> routes) {
        String normalised = (repoPath.startsWith("/") ? repoPath : "/" + repoPath).replaceAll("\\.git$", "");

        Map.Entry<String, SshProviderTarget> match = routes.entrySet().stream()
                .filter(e -> normalised.equals(e.getKey()) || normalised.startsWith(e.getKey() + "/"))
                .max(Comparator.comparingInt(e -> e.getKey().length()))
                .orElseThrow(() -> new IllegalArgumentException("Unknown provider path in: " + repoPath
                        + " (configured provider paths: " + routes.keySet() + ")"));

        String remainder = normalised.substring(match.getKey().length()).replaceFirst("^/", "");
        // Splits at the last separator, so a GitLab subgroup project keeps its whole namespace as the owner. The
        // permission grants evaluated later target owner and name, so this boundary has to be the one the HTTP
        // transport uses.
        RepoPath parsed = RepoPath.parse(remainder)
                .orElseThrow(() -> new IllegalArgumentException("Invalid repository path: " + repoPath + " (expected "
                        + match.getKey() + "/{owner}/{repo}.git)"));
        String owner = parsed.owner();
        String repo = parsed.name();
        SshProviderTarget target = match.getValue();
        // Forward over the provider's SSH endpoint, not getUri() — for a combined HTTP+SSH entry (fogwall#531) getUri()
        // is the https endpoint while the push must be forwarded over ssh. getSshUri() is always present here because
        // only SSH-enabled providers are routed onto the SSH server.
        URI sshUpstream = target.provider()
                .getSshUri()
                .orElseThrow(() -> new IllegalStateException("Provider '"
                        + target.provider().getName() + "' has no SSH endpoint but was routed to the SSH server"));
        String upstreamUrl = sshUpstream + parsed.slug() + ".git";
        return new RepoRoute(target, owner, repo, upstreamUrl, parsed.slug());
    }

    record RepoRoute(SshProviderTarget target, String owner, String repo, String upstreamUrl, String repoSlug) {
        FogwallProvider provider() {
            return target.provider();
        }

        ServerReceivePackFactory receivePackFactory() {
            return target.receivePackFactory();
        }
    }

    private void runReceivePack(
            String sshUser,
            UserEntry resolvedUser,
            String connectingFingerprint,
            String authSocket,
            ClientLivenessCheck liveness) {
        int exitCode = 0;
        SshAgent agent = null;
        QuarantineObjectStore quarantine = null;
        try {
            RepoRoute route;
            try {
                route = resolveRoute(repoPath, routes);
            } catch (IllegalArgumentException e) {
                writeError(e.getMessage());
                exitCode = 128;
                return;
            }
            String upstreamUrl = route.upstreamUrl();
            String repoSlug = route.repoSlug();

            // Look up the forwarded SSH agent by the proxy ID that MINA SSHD stored in the
            // channel env as SSH_AUTH_SOCK when the client's ssh -A request was processed.
            agent = agentFactory.getForwardedAgent(authSocket);
            if (agent == null || !agent.isOpen()) {
                writeError(
                        "SSH agent forwarding required — connect with 'ssh -A' or set 'ForwardAgent yes' in ~/.ssh/config");
                exitCode = 128;
                return;
            }

            log.info("SSH git-receive-pack: user='{}' path={} -> {}", sshUser, repoPath, upstreamUrl);

            TransportConfigCallback transportConfig =
                    SshUpstreamTransport.forwardedAgent(agent, knownHostsFile, trustOnFirstUse);
            var pushTransport = new PushTransport.Ssh(resolvedUser, connectingFingerprint, transportConfig, liveness);

            // The connecting key fingerprint is this session's authenticated identity, and so is the principal
            // for the mirror cache's per-principal fetch cooldown — the forwarded agent is what authorizes
            // upstream, and a different key must not inherit another key's verification.
            Repository localRepo = cache.getOrClone(upstreamUrl, null, transportConfig, connectingFingerprint);

            // Receive into a scratch store so a rejected push leaves nothing behind in the shared mirror.
            // Unlike the HTTP path there is no servlet request to hang the lifetime off, so it is scoped here.
            // Minted here so the quarantine directory on disk carries the same id as the audit record.
            String pushId = UUID.randomUUID().toString();
            quarantine = QuarantineObjectStore.createOrNull(localRepo, pushId);
            Repository target = quarantine != null ? quarantine.getRepository() : localRepo;

            ReceivePack rp = route.receivePackFactory()
                    .createForSsh(target, sshUser, repoSlug, upstreamUrl, pushTransport, quarantine, pushId);
            rp.setBiDirectionalPipe(true); // factory defaults to false for HTTP; SSH is bidirectional
            rp.receive(in, out, err);

        } catch (SshChannelClosedException e) {
            // Expected when the client disconnects mid-wait: ReceivePack.close() tries to flush final sideband
            // data after we've already detected the disconnect and canceled the push. Nothing left to report to.
            log.debug("SSH channel already closed for {} (client disconnected)", repoPath);
            exitCode = 1;
        } catch (Exception e) {
            log.error("SSH git-receive-pack failed for {}", repoPath, e);
            writeError("Internal error: " + e.getMessage());
            exitCode = 1;
        } finally {
            if (quarantine != null) {
                quarantine.close();
            }
            if (agent != null) {
                try {
                    agent.close();
                } catch (IOException ignored) {
                }
            }
            if (exitCallback != null) {
                exitCallback.onExit(exitCode);
            }
        }
    }

    private void writeError(String message) {
        try {
            err.write(("error: " + message + "\n").getBytes());
            err.flush();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void destroy(ChannelSession channel) {}
}
