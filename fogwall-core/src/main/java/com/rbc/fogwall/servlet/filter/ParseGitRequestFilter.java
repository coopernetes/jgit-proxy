package com.rbc.fogwall.servlet.filter;

import static com.rbc.fogwall.git.GitClientUtils.AnsiColor.RED;
import static com.rbc.fogwall.git.GitClientUtils.SymbolCodes.CROSS_MARK;
import static com.rbc.fogwall.git.GitClientUtils.SymbolCodes.NO_ENTRY;
import static com.rbc.fogwall.git.GitClientUtils.sym;
import static com.rbc.fogwall.servlet.FogwallServlet.GIT_REQUEST_ATTR;

import com.rbc.fogwall.git.GitClientUtils;
import com.rbc.fogwall.git.GitRequestDetails;
import com.rbc.fogwall.git.HttpOperation;
import com.rbc.fogwall.git.RepoPath;
import com.rbc.fogwall.provider.FogwallProvider;
import com.rbc.fogwall.servlet.PushTooLargeException;
import com.rbc.fogwall.servlet.RequestBodyWrapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.transport.PacketLineIn;

/**
 * Filter that extracts details about a git request and adds them to the request attributes. This filter is used to
 * extract the details so that they can be used by other filters for processing. This filter runs after the default
 * {@link ForceGitClientFilter}.
 */
@Slf4j
public class ParseGitRequestFilter extends ProviderAwareFogwallFilter<FogwallProvider> {

    private static final int ORDER = Integer.MIN_VALUE + 1;

    /** A full git object id: 40 lowercase hex characters. Covers the all-zero create/delete sentinel. */
    private static final Pattern OBJECT_ID = Pattern.compile("^[0-9a-f]{40}$");

    /** receive-pack capability under which {@code git push -o} option lines travel. */
    private static final String PUSH_OPTIONS_CAPABILITY = "push-options";

    private final long maxPushBytes;

    public ParseGitRequestFilter(FogwallProvider provider) {
        this(provider, 0);
    }

    /** @param maxPushBytes largest request body to accept, in bytes; 0 disables the check */
    public ParseGitRequestFilter(FogwallProvider provider, long maxPushBytes) {
        super(ORDER, provider);
        this.maxPushBytes = maxPushBytes;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;

        // Cheap pre-check: reject a declared over-size body without reading it at all. Clients using chunked
        // encoding declare no length, so this is an optimisation and the wrapper's counting read is the
        // actual bound.
        long declared = httpRequest.getContentLengthLong();
        if (maxPushBytes > 0 && declared > maxPushBytes) {
            sendTooLarge(httpRequest, (HttpServletResponse) response, maxPushBytes, declared);
            return;
        }

        // Create the wrapper to capture the body
        RequestBodyWrapper wrapper;
        try {
            wrapper = new RequestBodyWrapper(httpRequest, maxPushBytes);
        } catch (PushTooLargeException e) {
            sendTooLarge(httpRequest, (HttpServletResponse) response, e.getLimitBytes(), -1);
            return;
        }

        // Parse the git request details
        GitRequestDetails requestDetails = parse(wrapper);

        // Add the details to the request attributes
        wrapper.setAttribute(GIT_REQUEST_ATTR, requestDetails);

        if (System.getenv().containsKey("fogwall_DEBUG_CLIENT")
                && !System.getenv("fogwall_DEBUG_CLIENT").equals("")) {
            log.info("remote addr: {}", request.getRemoteAddr());
            log.info("user-agent: {}", ((HttpServletRequest) request).getHeader("User-Agent"));
        }

        // Block rejected requests (multi-ref pushes, invalid repository paths) immediately — do not
        // let them reach downstream filters
        if (requestDetails.getResult() == GitRequestDetails.GitResult.REJECTED) {
            String titleText =
                    requestDetails.getRejectionTitle() != null ? requestDetails.getRejectionTitle() : "Request Blocked";
            String title = sym(NO_ENTRY) + "  " + titleText;
            String message = sym(CROSS_MARK) + "  " + requestDetails.getReason();
            sendGitError(wrapper, (HttpServletResponse) response, GitClientUtils.format(title, message, RED, null));
            return;
        }

        // Continue with the wrapped request (important!)
        chain.doFilter(wrapper, response);
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // no-op
    }

    /**
     * Reports an over-size body to the git client.
     *
     * <p>Sent via {@code sendGitError} rather than an HTTP 413 because git only surfaces protocol-level errors to the
     * user; a bare status code produces an opaque failure. {@code declared} is the client's {@code Content-Length}, or
     * -1 when the limit was hit mid-read and the true size is unknown.
     */
    private void sendTooLarge(HttpServletRequest request, HttpServletResponse response, long limitBytes, long declared)
            throws IOException {
        log.warn(
                "Rejecting request body over the {}-byte limit (declared {}): {}",
                limitBytes,
                declared >= 0 ? declared : "unknown, chunked",
                request.getRequestURI());
        String title = sym(NO_ENTRY) + "  Push Blocked - Too Large";
        String sizeLine = declared >= 0
                ? sym(CROSS_MARK) + "  This push is " + humanReadable(declared) + "; the limit is "
                        + humanReadable(limitBytes) + "."
                : sym(CROSS_MARK) + "  This push exceeds the " + humanReadable(limitBytes) + " limit.";
        String message = sizeLine + "\n\n"
                + "Large files usually mean binaries or generated output that don't belong in git history.\n"
                + "If the content is genuinely needed, contact an administrator — a one-off import is normally\n"
                + "seeded directly upstream rather than pushed through the proxy.";
        sendGitError(request, response, GitClientUtils.format(title, message, RED, null));
    }

    private static String humanReadable(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) return String.format("%.1f GiB", bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024L * 1024L) return String.format("%.0f MiB", bytes / (1024.0 * 1024));
        return bytes + " bytes";
    }

    /**
     * Parse the {@link GitRequestDetails} details from the request body.
     *
     * @param request The HTTP request
     * @return The parsed push request
     */
    public GitRequestDetails parse(RequestBodyWrapper request) {
        var gr = new GitRequestDetails();
        gr.setProvider(provider);
        gr.getFilters().add(this);
        var op = determineOperation(request);
        gr.setOperation(op);
        // Reject a path that does not name a repository before it reaches URL rules, permission checks, or
        // upstream URL construction — the servlet container's URI normalization must not be the only defense.
        var repoPath = RepoPath.parse(request.getPathInfo());
        if (repoPath.isEmpty()) {
            log.warn("Rejecting request with invalid repository path: {}", request.getPathInfo());
            gr.setRepoRef(GitRequestDetails.RepoRef.builder()
                    .slug(request.getPathInfo())
                    .build());
            gr.setResult(GitRequestDetails.GitResult.REJECTED);
            gr.setRejectionTitle("Request Blocked - Invalid Repository Path");
            gr.setReason("Repository owner and name may only contain letters, digits, '.', '_' and '-'.");
            return gr;
        }
        gr.setRepoRef(GitRequestDetails.RepoRef.builder()
                .owner(repoPath.get().owner())
                .name(repoPath.get().name())
                .slug(repoPath.get().slug())
                .build());
        if (op == HttpOperation.INFO) {
            gr.setResult(GitRequestDetails.GitResult.ALLOWED);
        }
        if (op == HttpOperation.PUSH) {
            try {
                // Read packet line using JGit
                var pli = new PacketLineIn(request.getInputStream());
                String packetLine = pli.readStringRaw();

                // Skip shallow pkt-lines sent by shallow-clone clients before the ref update
                while (packetLine.startsWith("shallow ")) {
                    packetLine = pli.readStringRaw();
                }

                // A signed push (git push --signed) replaces the ref-update line with a push-cert
                // block whose second line is certificate text, so without this check it would fall
                // through to the multi-ref rejection below and be blamed on multi-branch pushing.
                // No supported upstream advertises the push-cert capability, so a stock client never
                // sends this; reject it by name rather than parse into the certificate.
                if (packetLine.startsWith("push-cert")) {
                    log.warn("Rejecting signed push (push-cert): the capability is not supported");
                    gr.setResult(GitRequestDetails.GitResult.REJECTED);
                    gr.setRejectionTitle("Push Blocked - Signed Push Not Supported");
                    gr.setReason(
                            "Signed pushes (git push --signed) are not supported. Please push again without --signed.");
                    return gr;
                }

                // Push options (git push -o key=value) are commands to the upstream — GitLab opens merge
                // requests and skips CI from them, Gitea/Forgejo flip repository visibility — that travel
                // after the ref updates and are never inspected by the filter chain. The proxy relays the
                // body verbatim, so it cannot strip them; reject the request when the client negotiated
                // the capability instead. The capability list follows the NUL on the first ref line.
                if (hasCapability(packetLine, PUSH_OPTIONS_CAPABILITY)) {
                    log.warn("Rejecting push that negotiated push-options: the capability is not supported");
                    gr.setResult(GitRequestDetails.GitResult.REJECTED);
                    gr.setRejectionTitle("Push Blocked - Push Options Not Supported");
                    gr.setReason("Push options (git push -o ...) are not supported. Please push again without -o.");
                    return gr;
                }

                // CVE-2025-54583: Reject multi-ref pushes. Read the next pkt-line — it must
                // be a flush packet (0000). If it's another ref update, the client is pushing
                // multiple branches and we must reject.
                String nextLine = pli.readString();
                if (!PacketLineIn.isEnd(nextLine)) {
                    log.warn("Multi-ref push detected — rejecting. First ref: {}", packetLine.trim());
                    gr.setResult(GitRequestDetails.GitResult.REJECTED);
                    gr.setRejectionTitle("Push Blocked - Multi-Branch Push");
                    gr.setReason("Multi-branch pushes are not allowed. Please push one branch at a time.");
                    return gr;
                }

                // Parse old SHA, new SHA, ref from the pkt-line. The client's capability list follows
                // a NUL byte (its entries are themselves space-separated), so strip it before splitting
                // the ref update itself on spaces. The object ids must be well-formed 40-hex SHAs (the
                // zero sentinel included) — they become push-record keys and JGit resolve() inputs
                // downstream, and nothing there should ever see a value a real git client could not
                // have sent.
                String[] parts = packetLine.split("\0")[0].split(" ");
                if (parts.length != 3
                        || !OBJECT_ID.matcher(parts[0]).matches()
                        || !OBJECT_ID.matcher(parts[1]).matches()) {
                    log.warn("Rejecting push with malformed ref update line: {}", packetLine.trim());
                    return rejectMalformed(gr, "The ref update line is not a valid git push command.");
                }
                gr.setCommitFrom(parts[0]);
                gr.setCommitTo(parts[1]);
                gr.setBranch(parts[2].trim());
            } catch (IOException | RuntimeException e) {
                // Fail closed with an accurate reason: a body this filter cannot parse must not travel
                // down the chain as a PENDING push that only later filters happen to stop.
                log.warn("Rejecting push whose request body could not be parsed", e);
                return rejectMalformed(gr, "The request body could not be parsed as a git push.");
            }
        }
        return gr;
    }

    /** True when the client's capability list on a ref-update line contains {@code capability} as a whole token. */
    static boolean hasCapability(String refUpdateLine, String capability) {
        int nul = refUpdateLine.indexOf('\0');
        if (nul < 0) return false;
        for (String token : refUpdateLine.substring(nul + 1).trim().split(" ")) {
            if (token.equals(capability)) return true;
        }
        return false;
    }

    /** Marks the request rejected as a malformed push, with a reason the git client will see verbatim. */
    private static GitRequestDetails rejectMalformed(GitRequestDetails gr, String reason) {
        gr.setResult(GitRequestDetails.GitResult.REJECTED);
        gr.setRejectionTitle("Push Blocked - Malformed Push Request");
        gr.setReason(reason + " If a normal git push produced this, please contact your administrator.");
        return gr;
    }
}
