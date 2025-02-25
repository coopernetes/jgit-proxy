package org.finos.gitproxy.servlet.filter;

import static org.eclipse.jgit.lib.Constants.PACK_SIGNATURE;
import static org.finos.gitproxy.servlet.GitProxyProviderServlet.GIT_REQUEST_ATTRIBUTE;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.transport.PacketLineIn;
import org.eclipse.jgit.util.RawParseUtils;
import org.finos.gitproxy.git.*;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.servlet.RequestBodyWrapper;
import org.springframework.core.Ordered;

/**
 * Filter that extracts details about a git request and adds them to the request attributes. This filter is used to
 * extract the details so that they can be used by other filters for processing. This filter runs after the default
 * {@link ForceGitClientFilter}.
 */
@Slf4j
public class ParseRequestFilter extends AbstractProviderAwareGitProxyFilter implements RepositoryUrlFilter {

    private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    public ParseRequestFilter(GitProxyProvider provider) {
        super(ORDER, Set.of(HttpOperation.PUSH, HttpOperation.FETCH, HttpOperation.INFO), provider);
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var wrapper = new RequestBodyWrapper(request);
        var pushRequest = parse(wrapper);
        request.setAttribute(GIT_REQUEST_ATTRIBUTE, pushRequest);
        chain.doFilter(wrapper, response);
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
        gr.setOwner(getOwner(request.getPathInfo()));
        gr.setName(getName(request.getPathInfo()));
        gr.setSlug(getSlug(request.getPathInfo()));

        if (op == HttpOperation.INFO) {
            gr.setResult(GitRequestDetails.GitResult.ALLOWED);
            return gr;
        }

        if (op == HttpOperation.PUSH) {
            try {
                // Read packet line using JGit
                var pli = new PacketLineIn(request.getInputStream());
                String packetLine = pli.readStringRaw();

                // Read remaining data (pack file)
                byte[] packData = readRemainingData(request.getInputStream());

                // parse the packet line and pack data
                var pushInfo = GitPackParser.parsePush(packetLine, packData);

                // Set all relevant fields from pushInfo to GitRequestDetails
                gr.setBranch(pushInfo.getReference());
                gr.setCommit(pushInfo.getCommit());

                if (log.isDebugEnabled()) {
                    logPushDetails(gr.getId(), packetLine, packData);
                }
            } catch (IOException e) {
                log.error("Error parsing push request", e);
            }
        }
        return gr;
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
            Files.write(Path.of("packetline-" + id + ".txt"),
                    packetLine.getBytes(StandardCharsets.UTF_8));
            Files.write(Path.of("body-" + id + ".txt"), packData);
        } catch (IOException e) {
            log.error("Error writing debug files", e);
        }
    }
}
