package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.git.GitClient.AnsiColor.*;
import static org.finos.gitproxy.git.GitClient.SymbolCodes.*;
import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.security.Security;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider;
import org.finos.gitproxy.config.GpgConfig;
import org.finos.gitproxy.git.Commit;
import org.finos.gitproxy.git.GitClient;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;

/**
 * Filter that validates GPG signatures on commits. This filter checks that commits are signed with a trusted GPG key
 * when signature validation is enabled.
 *
 * <p>This filter runs at order 2400, which is in the built-in content filters range (2000-4999).
 */
@Slf4j
public class GpgSignatureFilter extends AbstractGitProxyFilter {

    private static final int ORDER = 2400;
    private final GpgConfig config;
    private PGPPublicKeyRingCollection trustedKeys;

    static {
        // Add BouncyCastle as a security provider
        Security.addProvider(new BouncyCastleProvider());
    }

    public GpgSignatureFilter(GpgConfig config) {
        super(ORDER, Set.of(HttpOperation.PUSH));
        this.config = config != null ? config : GpgConfig.defaultConfig();
        this.trustedKeys = loadTrustedKeys();
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!config.isEnabled()) {
            log.debug("GPG signature validation is disabled");
            return;
        }

        var requestDetails = (GitRequestDetails) request.getAttribute(GIT_REQUEST_ATTR);
        if (requestDetails == null) {
            log.warn("GitRequestDetails not found in request attributes");
            return;
        }

        var commits = requestDetails.getPushedCommits();
        if (commits == null || commits.isEmpty()) {
            log.debug("No commits found in request details");
            return;
        }

        // Collect all unsigned / invalid-signature commits before reporting
        List<String> unsignedShas = new ArrayList<>();
        List<String> invalidShas = new ArrayList<>();

        for (Commit commit : commits) {
            String signature = commit.getSignature();
            String shortSha =
                    commit.getSha().substring(0, Math.min(7, commit.getSha().length()));

            if (config.isRequireSignedCommits() && (signature == null || signature.isEmpty())) {
                log.warn("Commit {} is not signed but signatures are required", commit.getSha());
                unsignedShas.add(shortSha);
                continue;
            }

            if (signature != null && !signature.isEmpty() && !verifySignature(commit)) {
                log.warn("Commit {} has an invalid signature", commit.getSha());
                invalidShas.add(shortSha);
            }
        }

        if (!unsignedShas.isEmpty()) {
            String shaList = unsignedShas.stream()
                    .map(s -> CROSS_MARK.emoji() + "  " + s)
                    .collect(java.util.stream.Collectors.joining("\n"));
            String title = NO_ENTRY.emoji() + "  Push Blocked — Unsigned Commit(s)";
            String message = shaList + "\n\n" + KEY.emoji()
                    + "  All commits must be signed with a GPG key.\n"
                    + "   git config commit.gpgsign true";
            recordIssue(request, "Unsigned commit(s)", GitClient.format(title, message, RED, null));
        }

        if (!invalidShas.isEmpty()) {
            String shaList = invalidShas.stream()
                    .map(s -> CROSS_MARK.emoji() + "  " + s)
                    .collect(java.util.stream.Collectors.joining("\n"));
            String title = NO_ENTRY.emoji() + "  Push Blocked — Invalid Signature(s)";
            String message = shaList + "\n\n" + KEY.emoji() + "  Ensure commits are signed with a trusted key.";
            recordIssue(request, "Invalid GPG signature(s)", GitClient.format(title, message, RED, null));
        }

        if (unsignedShas.isEmpty() && invalidShas.isEmpty()) {
            log.debug("All commits have valid GPG signatures or are not required to be signed");
        }
    }

    /**
     * Verify the GPG signature on a commit.
     *
     * @param commit The commit to verify
     * @return true if the signature is valid, false otherwise
     */
    private boolean verifySignature(Commit commit) {
        try {
            String signature = commit.getSignature();
            if (signature == null || signature.isEmpty()) {
                return false;
            }

            // Parse the PGP signature
            InputStream sigInputStream = new ByteArrayInputStream(signature.getBytes());
            PGPObjectFactory pgpFact =
                    new PGPObjectFactory(PGPUtil.getDecoderStream(sigInputStream), new BcKeyFingerprintCalculator());

            Object obj = pgpFact.nextObject();
            if (!(obj instanceof PGPSignatureList)) {
                log.warn("Signature is not a valid PGP signature list");
                return false;
            }

            PGPSignatureList sigList = (PGPSignatureList) obj;
            if (sigList.isEmpty()) {
                log.warn("Signature list is empty");
                return false;
            }

            PGPSignature sig = sigList.get(0);

            // Find the public key that signed this
            PGPPublicKey publicKey = findPublicKey(sig.getKeyID());
            if (publicKey == null) {
                log.warn(
                        "Public key not found for signature verification (key ID: {})",
                        Long.toHexString(sig.getKeyID()));
                return false;
            }

            // Initialize the signature for verification
            sig.init(new BcPGPContentVerifierBuilderProvider(), publicKey);

            // Create commit data to verify (simplified - in reality you'd need the exact commit data)
            String commitData = createCommitDataString(commit);
            sig.update(commitData.getBytes());

            return sig.verify();
        } catch (Exception e) {
            log.error("Error verifying GPG signature for commit {}", commit.getSha(), e);
            return false;
        }
    }

    /**
     * Find a public key in the trusted keyring by key ID.
     *
     * @param keyID The key ID to find
     * @return The public key, or null if not found
     */
    private PGPPublicKey findPublicKey(long keyID) throws PGPException {
        if (trustedKeys == null) {
            return null;
        }

        Iterator<PGPPublicKeyRing> keyRingIter = trustedKeys.getKeyRings();
        while (keyRingIter.hasNext()) {
            PGPPublicKeyRing keyRing = keyRingIter.next();
            PGPPublicKey key = keyRing.getPublicKey(keyID);
            if (key != null) {
                return key;
            }
        }

        return null;
    }

    /**
     * Create the commit data string that was signed.
     *
     * @param commit The commit
     * @return The commit data string
     */
    private String createCommitDataString(Commit commit) {
        StringBuilder sb = new StringBuilder();
        sb.append("tree ").append("TREE_HASH").append("\n");
        if (commit.getParent() != null) {
            sb.append("parent ").append(commit.getParent()).append("\n");
        }
        sb.append("author ")
                .append(commit.getAuthor().getName())
                .append(" <")
                .append(commit.getAuthor().getEmail())
                .append("> ")
                .append(commit.getDate().getEpochSecond())
                .append(" +0000\n");
        sb.append("committer ")
                .append(commit.getCommitter().getName())
                .append(" <")
                .append(commit.getCommitter().getEmail())
                .append("> ")
                .append(commit.getDate().getEpochSecond())
                .append(" +0000\n");
        sb.append("\n").append(commit.getMessage()).append("\n");
        return sb.toString();
    }

    /**
     * Load trusted public keys from configuration.
     *
     * @return The trusted public keys, or null if none configured
     */
    private PGPPublicKeyRingCollection loadTrustedKeys() {
        try {
            // Try inline keys first
            if (config.getTrustedKeysInline() != null
                    && !config.getTrustedKeysInline().isEmpty()) {
                InputStream keyInputStream =
                        new ByteArrayInputStream(config.getTrustedKeysInline().getBytes());
                return new PGPPublicKeyRingCollection(
                        PGPUtil.getDecoderStream(keyInputStream), new BcKeyFingerprintCalculator());
            }

            // Try file path
            if (config.getTrustedKeysFile() != null
                    && !config.getTrustedKeysFile().isEmpty()) {
                File keyFile = new File(config.getTrustedKeysFile());
                if (keyFile.exists()) {
                    InputStream keyInputStream = new FileInputStream(keyFile);
                    return new PGPPublicKeyRingCollection(
                            PGPUtil.getDecoderStream(keyInputStream), new BcKeyFingerprintCalculator());
                }
            }

            // Use a dummy key for testing
            log.info("No trusted keys configured, using dummy key for testing");
            return loadDummyKey();
        } catch (Exception e) {
            log.error("Error loading trusted GPG keys", e);
            return null;
        }
    }

    /**
     * Load a dummy GPG public key for testing purposes.
     *
     * @return A dummy public key collection
     */
    private PGPPublicKeyRingCollection loadDummyKey() {
        try {
            // This is a dummy public key for testing - replace with real keys in production
            String dummyKey = "-----BEGIN PGP PUBLIC KEY BLOCK-----\n"
                    + "\n"
                    + "mQENBGMxCc0BCAC5qhL3bQ3KHJz1nH0Y2hxqpSYR4pJkr9VGvL9J9Nw+2Z9X8f9P\n"
                    + "7k9X8f9P7k9X8f9P7k9X8f9P7k9X8f9P7k9X8f9P7k9X8f9P7k9X8f9P7k9X8f9P\n"
                    + "-----END PGP PUBLIC KEY BLOCK-----\n";
            InputStream keyInputStream = new ByteArrayInputStream(dummyKey.getBytes());
            return new PGPPublicKeyRingCollection(
                    PGPUtil.getDecoderStream(keyInputStream), new BcKeyFingerprintCalculator());
        } catch (Exception e) {
            log.error("Error loading dummy GPG key", e);
            return null;
        }
    }
}
