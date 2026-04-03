package org.finos.gitproxy.git;

import static org.finos.gitproxy.git.GitClientUtils.ZERO_OID;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.util.RawParseUtils;

@Slf4j
public class GitReceivePackParser {
    private static final int OBJ_COMMIT = 1;
    //    private static final int OBJ_TREE = 2;
    //    private static final int OBJ_BLOB = 3;
    //    private static final int OBJ_TAG = 4;
    private static final int OBJ_OFS_DELTA = 6;
    private static final int OBJ_REF_DELTA = 7;

    @Data
    @Builder
    public static class PushInfo {
        private String oldCommit;
        private String newCommit;
        private String reference;
        private Commit commit;
    }

    public static PushInfo parsePush(String packetLine, byte[] packData) throws IOException {
        // Parse packet line (oldCommit newCommit reference)
        String[] parts = packetLine.split(" ");
        String oldCommit = parts[0];
        String newCommit = parts[1];
        String reference = parts[2].replace("\u0000", "").trim();

        // For branch deletion (newCommit is all zeros), there's no pack data
        Commit commit = null;
        if (!newCommit.equals(ZERO_OID) && packData != null && packData.length > 0) {
            // Parse the commit content from pack data
            try {
                commit = parsePackData(packData);

                // Set the correct SHA values from the packet line
                if (commit != null) {
                    commit.setSha(newCommit);

                    // If parent is empty from pack data, use the old commit SHA
                    if (commit.getParent() == null
                            || commit.getParent().isEmpty()
                            || commit.getParent().equals(ZERO_OID)) {
                        commit.setParent(oldCommit);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse pack data", e);
            }
        }

        return PushInfo.builder()
                .oldCommit(oldCommit)
                .newCommit(newCommit)
                .reference(reference)
                .commit(commit)
                .build();
    }

    public static Commit parsePackData(byte[] data) throws IOException {
        int pos = findPackSignature(data);

        // Check if pack signature was found
        if (pos == -1) {
            throw new IOException("No PACK signature found in data");
        }

        // Read pack header
        byte[] header = new byte[12];
        System.arraycopy(data, pos, header, 0, 12);

        // Skip version and entry count validation
        pos += 12;

        // Only parse the first commit object
        PackEntry entry = parseEntry(data, pos);
        if (entry != null && entry.type == OBJ_COMMIT) {
            return parseCommitContent(entry.data);
        }
        throw new RuntimeException("No commit object found in pack data");
    }

    private static int findPackSignature(byte[] data) {
        for (int i = 0; i < data.length - 4; i++) {
            if (data[i] == 'P' && data[i + 1] == 'A' && data[i + 2] == 'C' && data[i + 3] == 'K') {
                return i;
            }
        }
        return -1;
    }

    private static PackEntry parseEntry(byte[] data, int pos) throws IOException {
        if (pos >= data.length) {
            return null;
        }

        int headerByte = data[pos] & 0xFF;
        int type = (headerByte >> 4) & 7;
        long size = headerByte & 0x0F;
        int shift = 4;
        pos++;

        while ((headerByte & 0x80) != 0) {
            if (pos >= data.length) {
                return null;
            }
            headerByte = data[pos++] & 0xFF;
            size |= ((long) (headerByte & 0x7F)) << shift;
            shift += 7;
        }

        byte[] content;
        if (type == OBJ_REF_DELTA || type == OBJ_OFS_DELTA) {
            pos += (type == OBJ_REF_DELTA) ? 20 : 1;
        }

        content = inflateData(data, pos);
        if (content == null) {
            return null;
        }

        pos += findDeflatedSize(data, pos);
        return new PackEntry(type, content, pos);
    }

    private static byte[] inflateData(byte[] data, int pos) {
        try {
            Inflater inflater = new Inflater();
            inflater.setInput(data, pos, data.length - pos);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            while (!inflater.finished()) {
                int count = inflater.inflate(buf);
                if (count == 0 && inflater.needsInput()) {
                    break;
                }
                out.write(buf, 0, count);
            }
            inflater.end();
            return out.toByteArray();
        } catch (DataFormatException e) {
            log.error("Failed to inflate data", e);
            return null;
        }
    }

    private static int findDeflatedSize(byte[] data, int pos) {
        int size = 0;
        while (pos < data.length) {
            size++;
            if ((data[pos++] & 0x80) == 0) {
                break;
            }
        }
        return size;
    }

    private static Commit parseCommitContent(byte[] content) {
        String raw = RawParseUtils.decode(content);

        // Get author offset and parse author
        int authorOffset = RawParseUtils.author(content, 0);
        PersonIdent author = RawParseUtils.parsePersonIdent(content, authorOffset);

        // Get committer offset and parse committer
        int committerOffset = RawParseUtils.committer(content, 0);
        PersonIdent committer = RawParseUtils.parsePersonIdent(content, committerOffset);

        String parent = extractHeaderValue(raw, "parent");
        String signature = extractSignature(raw);
        String message = raw.substring(RawParseUtils.commitMessage(content, 0));

        return Commit.builder()
                // Don't set SHA here, will be set from packet line
                .parent(parent) // Keep this, but it will be overridden if empty
                .author(Contributor.builder()
                        .name(author.getName())
                        .email(author.getEmailAddress())
                        .build())
                .committer(Contributor.builder()
                        .name(committer.getName())
                        .email(committer.getEmailAddress())
                        .build())
                .message(message.trim())
                .date(Instant.ofEpochSecond(committer.getWhen().getTime() / 1000))
                .signature(signature) // Add the signature
                .build();
    }

    private static String extractSignature(String content) {
        int sigStart = content.indexOf("gpgsig ");
        if (sigStart < 0) return null;

        // Find the start of the signature block
        sigStart += 7; // length of "gpgsig "
        int sigEnd = content.indexOf("\n ", sigStart);
        if (sigEnd < 0) return null;

        // Build the complete signature by collecting lines that start with a space
        StringBuilder signature = new StringBuilder(content.substring(sigStart, sigEnd));
        int pos = sigEnd;

        while (true) {
            int nextLine = content.indexOf("\n", pos + 1);
            if (nextLine < 0 || content.charAt(pos + 1) != ' ') {
                break;
            }
            signature.append(content.substring(pos + 1, nextLine).substring(1)); // Skip the space
            pos = nextLine;
        }

        return signature.toString();
    }

    private static String extractHeaderValue(String content, String header) {
        int start = content.indexOf(header + " ");
        if (start < 0) return null;
        start += header.length() + 1;
        int end = content.indexOf('\n', start);
        return content.substring(start, end);
    }

    private static class PackEntry {
        final int type;
        final byte[] data;
        final int nextPosition;

        PackEntry(int type, byte[] data, int nextPosition) {
            this.type = type;
            this.data = data;
            this.nextPosition = nextPosition;
        }
    }
}
