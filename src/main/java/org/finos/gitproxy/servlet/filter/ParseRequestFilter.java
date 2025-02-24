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
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.transport.PacketLineIn;
import org.finos.gitproxy.git.Commit;
import org.finos.gitproxy.git.Contributor;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
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
        // Parse the request body and return a push request
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
        }
        if (op == HttpOperation.PUSH) {
            var packetLine = getPacketLineFromRequest(gr.getId(), request);
            var body = parseBody(gr.getId(), request);
            gr.setCommits(parseCommits(packetLine, body));
            gr.setBranch(parseBranch(packetLine));
        }
        return gr;
    }

    private String getPacketLineFromRequest(UUID id, final RequestBodyWrapper request) {
        try {
            var pli = new PacketLineIn(request.getInputStream());
            var s = pli.readStringRaw();
            var f = new File("packetline-" + id + ".txt");
            var fos = new java.io.FileOutputStream(f);
            fos.write(s.getBytes());
            // DEBUG: 0000000000000000000000000000000000000000 b33de1211f0e5212bde08e0abcc1f666e4f89302 refs/heads/test
            // report-status-v2 side-band-64k object-format=sha1 agent=git/2.48.1
            return s;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] parseBody(UUID id, RequestBodyWrapper request) {
        try (var bis = new BufferedInputStream(request.getInputStream());
                var os = new ByteArrayOutputStream()) {
            var buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = bis.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
            var s = os.toString(StandardCharsets.UTF_8);
            var f = new File("body-" + id + ".txt");
            try (var fos = new FileOutputStream(f)) {
                fos.write(os.toByteArray());
            }

            return os.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // https://github.com/finos/git-proxy/blob/1cde9ac86c70786b28a47b1d2188e1b6dd578a9a/src/proxy/processors/push-action/parsePush.js#L17
    private String parseBranch(String packetLine) {
        return packetLine.split(" ")[2].replace("\u0000", "");
    }

    private List<Commit> parseCommits(String packetLine, byte[] rawData) {
        var packIdx = findPackSignature(rawData);
        if (packIdx == -1) {
            log.error("No PACK signature found in data");
            return Collections.emptyList();
        }

        byte[] packData = Arrays.copyOfRange(rawData, packIdx, rawData.length);
        var meta = getPackMeta(packData);
        if (meta == null || !meta.sig.equals("PACK")) {
            log.error("Invalid PACK signature: {}", meta != null ? meta.sig : "null");
            return Collections.emptyList();
        }

        log.debug("meta: {}", meta);
        var contentBuffer = Arrays.copyOfRange(packData, 12, packData.length);
        var contents = getContents(contentBuffer, meta.entries);
        return getCommitData(contents);
    }

    private int findPackSignature(byte[] data) {
        byte[] sig = PACK_SIGNATURE;
        for (int i = 0; i < data.length - sig.length; i++) {
            boolean found = true;
            for (int j = 0; j < sig.length; j++) {
                if (data[i + j] != sig[j]) {
                    found = false;
                    break;
                }
            }
            if (found) {
                return i;
            }
        }
        return -1;
    }

    private Meta getPackMeta(byte[] buffer) {
        if (buffer.length < 12) {
            log.error("Buffer too short for PACK header");
            return null;
        }

        String sig = new String(Arrays.copyOfRange(buffer, 0, 4), StandardCharsets.UTF_8);
        int version = ByteBuffer.wrap(buffer, 4, 4).getInt();
        int entries = ByteBuffer.wrap(buffer, 8, 4).getInt();

        return new Meta(sig, version, entries);
    }

    @ToString
    static class Meta {
        String sig;
        int version;
        int entries;

        Meta(String sig, int version, int entries) {
            this.sig = sig;
            this.version = version;
            this.entries = entries;
        }
    }

    @ToString
    private static class Content {
        private int item;
        private int value;
        private int type;
        private int size;
        private int deflatedSize;
        private String objectRef;
        private String content;
    }

    private List<Content> getContents(byte[] buffer, int entries) {
        List<Content> contents = new ArrayList<>();
        byte[] currentBuffer = buffer;

        for (int i = 0; i < entries; i++) {
            try {
                if (currentBuffer.length == 0) {
                    log.error("Buffer is empty while processing entry {}", i);
                    break;
                }
                ContentResult result = getContent(i, currentBuffer);
                log.debug("Content: {}", result.content);
                contents.add(result.content);
                // Include both the header size and the deflated content size
                int totalBytes = result.bytesRead;
                if (totalBytes >= currentBuffer.length) {
                    break;
                }
                currentBuffer = Arrays.copyOfRange(currentBuffer, totalBytes, currentBuffer.length);
            } catch (Exception e) {
                log.error("Error processing content", e);
                break;
            }
        }
        return contents;
    }

    private ContentResult getContent(int item, byte[] buffer) throws IOException {
        if (buffer.length == 0) {
            throw new IOException("Empty buffer");
        }

        byte firstByte = buffer[0];
        int more = (firstByte >> 7) & 0x1;
        int type = (firstByte >> 4) & 0x7;
        int size = firstByte & 0xf;
        int currentPosition = 1;

        // Read the variable-length size
        while (more == 1 && currentPosition < buffer.length) {
            byte nextByte = buffer[currentPosition];
            more = (nextByte >> 7) & 0x1;
            size = ((size + 1) << 7) | (nextByte & 0x7f);
            currentPosition++;
        }

        // Handle deltified objects
        String objectRef = null;
        if ((type == 6 || type == 7) && currentPosition + 20 <= buffer.length) {
            byte[] refBytes = Arrays.copyOfRange(buffer, currentPosition, currentPosition + 20);
            objectRef = bytesToHex(refBytes);
            currentPosition += 20;
        }

        if (currentPosition >= buffer.length) {
            throw new IOException("Buffer too short for content");
        }

        // Get the compressed content
        byte[] contentBuffer = Arrays.copyOfRange(buffer, currentPosition, buffer.length);
        UnpackResult unpackResult = unpack(contentBuffer);

        Content content = new Content();
        content.item = item;
        content.value = firstByte & 0xFF;
        content.type = type;
        content.size = size;
        content.deflatedSize = unpackResult.deflatedSize;
        content.objectRef = objectRef;
        content.content = unpackResult.content;

        ContentResult result = new ContentResult();
        result.content = content;
        result.bytesRead = currentPosition + unpackResult.deflatedSize;

        return result;
    }

    private static class ContentResult {
        Content content;
        int bytesRead;
    }

    private static class UnpackResult {
        String content;
        int deflatedSize;
    }

    private UnpackResult unpack(byte[] buffer) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(buffer);

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1024];
            while (!inflater.finished()) {
                int count = inflater.inflate(buf);
                outputStream.write(buf, 0, count);
            }

            UnpackResult unpackResult = new UnpackResult();
            unpackResult.content = outputStream.toString(StandardCharsets.UTF_8);
            unpackResult.deflatedSize = buffer.length;

            return unpackResult;
        } catch (DataFormatException e) {
            throw new IOException("Failed to inflate data", e);
        } finally {
            inflater.end();
        }
    }

    private List<Commit> getCommitData(List<Content> contents) {
        return contents.stream()
                .filter(x -> x.type == 1)
                .map(x -> {
                    String[] formattedContent = x.content.split("\n");
                    List<String> parts = Arrays.stream(formattedContent)
                            .filter(part -> !part.isEmpty())
                            .toList();

                    String tree = parts.stream()
                            .filter(t -> t.startsWith("tree "))
                            .findFirst()
                            .map(t -> t.replace("tree", "").trim())
                            .orElse("");

                    String parent = parts.stream()
                            .filter(t -> t.startsWith("parent "))
                            .findFirst()
                            .map(t -> t.replace("parent", "").trim())
                            .orElse("0000000000000000000000000000000000000000");

                    String authorLine = parts.stream()
                            .filter(t -> t.startsWith("author "))
                            .findFirst()
                            .map(t -> t.replace("author", "").trim())
                            .orElse("");

                    String committerLine = parts.stream()
                            .filter(t -> t.startsWith("committer "))
                            .findFirst()
                            .map(t -> t.replace("committer", "").trim())
                            .orElse("");

                    int indexOfMessages = Arrays.asList(formattedContent).indexOf("");
                    String message = indexOfMessages != -1
                            ? String.join(
                                    " ",
                                    Arrays.copyOfRange(
                                            formattedContent, indexOfMessages + 1, formattedContent.length - 1))
                            : "";

                    String[] committerParts = committerLine.split(" ");
                    String commitTimestamp = committerParts.length > 1 ? committerParts[committerParts.length - 2] : "";
                    Instant commitDate = Instant.ofEpochSecond(Long.parseLong(commitTimestamp))
                            .plusSeconds(parseTimezoneOffset(committerParts[committerParts.length - 1]));
                    String authorName = authorLine.split("<")[0].trim();
                    String authorEmail = authorLine.contains("<")
                            ? authorLine.substring(authorLine.indexOf("<") + 1, authorLine.indexOf(">"))
                            : "";

                    String commitName = committerLine.split("<")[0].trim();
                    String commitEmail = committerLine.contains("<")
                            ? committerLine.substring(committerLine.indexOf("<") + 1, committerLine.indexOf(">"))
                            : "";
                    return Commit.builder()
                            .id(parent)
                            .author(new Contributor(authorName, authorEmail))
                            .committer(new Contributor(commitName, commitEmail))
                            .message(message)
                            .date(commitDate)
                            .build();
                })
                .collect(Collectors.toList());
    }

    private static int parseTimezoneOffset(String timezone) {
        // Git format is "+0400" or "-0400"
        int sign = timezone.charAt(0) == '-' ? -1 : 1;
        int hours = Integer.parseInt(timezone.substring(0, 3));  // includes sign
        int minutes = Integer.parseInt(timezone.substring(3));
        // Convert to seconds: hours * 3600 + minutes * 60
        return hours * 3600 + (minutes * 60 * sign);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
