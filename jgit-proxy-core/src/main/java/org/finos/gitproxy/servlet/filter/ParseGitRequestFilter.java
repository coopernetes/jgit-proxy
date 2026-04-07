package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.git.GitClientUtils.AnsiColor.RED;
import static org.finos.gitproxy.git.GitClientUtils.SymbolCodes.CROSS_MARK;
import static org.finos.gitproxy.git.GitClientUtils.SymbolCodes.NO_ENTRY;
import static org.finos.gitproxy.git.GitClientUtils.sym;
import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.transport.PacketLineIn;
import org.finos.gitproxy.git.GitClientUtils;
import org.finos.gitproxy.git.GitReceivePackParser;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.servlet.RequestBodyWrapper;

/**
 * Filter that extracts details about a git request and adds them to the request attributes. This filter is used to
 * extract the details so that they can be used by other filters for processing. This filter runs after the default
 * {@link ForceGitClientFilter}.
 */
@Slf4j
public class ParseGitRequestFilter extends AbstractProviderAwareGitProxyFilter {

    private static final int ORDER = Integer.MIN_VALUE + 1;

    public ParseGitRequestFilter(GitProxyProvider provider) {
        super(ORDER, provider);
    }

    public ParseGitRequestFilter(GitProxyProvider provider, String pathPrefix) {
        super(ORDER, provider, pathPrefix);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;

        // Create the wrapper to capture the body
        RequestBodyWrapper wrapper = new RequestBodyWrapper(httpRequest);

        // Parse the git request details
        GitRequestDetails requestDetails = parse(wrapper);

        // Add the details to the request attributes
        wrapper.setAttribute(GIT_REQUEST_ATTR, requestDetails);

        if (System.getenv().containsKey("GITPROXY_DEBUG_CLIENT")
                && !System.getenv("GITPROXY_DEBUG_CLIENT").equals("")) {
            log.info("remote addr: {}", request.getRemoteAddr());
            log.info("user-agent: {}", ((HttpServletRequest) request).getHeader("User-Agent"));
        }

        // Block multi-ref pushes immediately — do not let them reach downstream filters
        if (requestDetails.getResult() == GitRequestDetails.GitResult.REJECTED) {
            String title = sym(NO_ENTRY) + "  Push Blocked - Multi-Branch Push";
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
        gr.setRepoRef(GitRequestDetails.RepoRef.builder()
                .owner(getOwner(request.getPathInfo()))
                .name(getName(request.getPathInfo()))
                .slug(getSlug(request.getPathInfo()))
                .build());
        if (op == HttpOperation.INFO) {
            gr.setResult(GitRequestDetails.GitResult.ALLOWED);
        }
        if (op == HttpOperation.PUSH) {
            try {
                // Read packet line using JGit
                var pli = new PacketLineIn(request.getInputStream());
                String packetLine = pli.readStringRaw();

                // CVE-2025-54583: Reject multi-ref pushes. Read the next pkt-line — it must
                // be a flush packet (0000). If it's another ref update, the client is pushing
                // multiple branches and we must reject.
                String nextLine = pli.readString();
                if (!PacketLineIn.isEnd(nextLine)) {
                    log.warn("Multi-ref push detected — rejecting. First ref: {}", packetLine.trim());
                    gr.setResult(GitRequestDetails.GitResult.REJECTED);
                    gr.setReason("Multi-branch pushes are not allowed. Please push one branch at a time.");
                    return gr;
                }

                // Read remaining data (pack file) — starts right after the consumed flush packet
                byte[] packData = readRemainingData(request.getInputStream());

                // parse the packet line and pack data
                var pushInfo = GitReceivePackParser.parsePush(packetLine, packData);

                // Set all relevant fields from pushInfo to GitRequestDetails
                gr.setBranch(pushInfo.getReference());
                gr.setCommit(pushInfo.getCommit());
                gr.setCommitFrom(pushInfo.getOldCommit());
                gr.setCommitTo(pushInfo.getNewCommit());

                if (null != System.getenv("GITPROXY_DEBUG_PACKET")) {
                    logPushDetails(gr.getId(), packetLine, packData);
                }
            } catch (IOException e) {
                log.error("Error parsing push request", e);
            }
        }
        return gr;
    }

    private static String getOwner(String pathInfo) {
        var parts = pathInfo.split("/");
        return parts.length < 3 ? pathInfo : parts[1];
    }

    private static String getName(String pathInfo) {
        var parts = pathInfo.split("/");
        return parts.length < 3 ? pathInfo : parts[2].replace(Constants.DOT_GIT_EXT, "");
    }

    private static String getSlug(String pathInfo) {
        var parts = pathInfo.split("/");
        if (parts.length < 3) return pathInfo;
        return "/" + String.join("/", parts[1], parts[2]).replace(Constants.DOT_GIT_EXT, "");
    }

    private byte[] readRemainingData(InputStream is) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            return baos.toByteArray();
        }
    }

    private void logPushDetails(UUID id, String packetLine, byte[] packData) {
        try {
            Files.writeString(Path.of("packetline-" + id + ".txt"), packetLine);
            Files.write(Path.of("body-" + id + ".txt"), packData);
        } catch (IOException e) {
            log.error("Error writing debug files", e);
        }
    }
}
