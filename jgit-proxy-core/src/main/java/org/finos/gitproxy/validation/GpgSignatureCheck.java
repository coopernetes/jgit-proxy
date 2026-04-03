package org.finos.gitproxy.validation;

import static org.finos.gitproxy.git.GitClientUtils.AnsiColor.*;
import static org.finos.gitproxy.git.GitClientUtils.SymbolCodes.*;
import static org.finos.gitproxy.git.GitClientUtils.sym;

import java.io.*;
import java.security.Security;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider;
import org.finos.gitproxy.config.GpgConfig;
import org.finos.gitproxy.git.Commit;
import org.finos.gitproxy.git.GitClientUtils;

/**
 * Validates GPG signatures on commits.
 *
 * <p>When {@link GpgConfig#isEnabled()} is {@code false} this check always passes without inspecting commits, matching
 * the default out-of-the-box behaviour.
 */
@Slf4j
public class GpgSignatureCheck implements CommitCheck {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private final GpgConfig config;
    private final PGPPublicKeyRingCollection trustedKeys;

    public GpgSignatureCheck(GpgConfig config) {
        this.config = config != null ? config : GpgConfig.defaultConfig();
        this.trustedKeys = loadTrustedKeys(this.config);
    }

    @Override
    public List<Violation> check(List<Commit> commits) {
        if (!config.isEnabled()) {
            return List.of();
        }

        List<Violation> violations = new ArrayList<>();
        for (Commit commit : commits) {
            String shortSha =
                    commit.getSha().substring(0, Math.min(7, commit.getSha().length()));
            String signature = commit.getSignature();

            if (config.isRequireSignedCommits() && (signature == null || signature.isEmpty())) {
                String reason = "not signed";
                String title = sym(NO_ENTRY) + "  Push Blocked — Unsigned Commit(s)";
                String message = sym(CROSS_MARK) + "  " + shortSha + "\n\n"
                        + sym(KEY) + "  All commits must be signed with a GPG key.\n"
                        + "   git config commit.gpgsign true";
                violations.add(new Violation(shortSha, reason, GitClientUtils.format(title, message, RED, null)));
                continue;
            }

            if (signature != null && !signature.isEmpty() && !verifySignature(commit)) {
                String reason = "invalid signature";
                String title = sym(NO_ENTRY) + "  Push Blocked — Invalid Signature(s)";
                String message = sym(CROSS_MARK) + "  " + shortSha + "\n\n" + sym(KEY)
                        + "  Ensure commits are signed with a trusted key.";
                violations.add(new Violation(shortSha, reason, GitClientUtils.format(title, message, RED, null)));
            }
        }
        return violations;
    }

    private boolean verifySignature(Commit commit) {
        try {
            InputStream sigInputStream =
                    new ByteArrayInputStream(commit.getSignature().getBytes());
            PGPObjectFactory pgpFact =
                    new PGPObjectFactory(PGPUtil.getDecoderStream(sigInputStream), new BcKeyFingerprintCalculator());

            Object obj = pgpFact.nextObject();
            if (!(obj instanceof PGPSignatureList sigList) || sigList.isEmpty()) {
                log.warn("Commit {} has malformed PGP signature", commit.getSha());
                return false;
            }

            PGPSignature sig = sigList.get(0);
            PGPPublicKey publicKey = findPublicKey(sig.getKeyID());
            if (publicKey == null) {
                log.warn(
                        "No trusted key for signature on commit {} (key ID: {})",
                        commit.getSha(),
                        Long.toHexString(sig.getKeyID()));
                return false;
            }

            sig.init(new BcPGPContentVerifierBuilderProvider(), publicKey);
            sig.update(createCommitDataString(commit).getBytes());
            return sig.verify();
        } catch (Exception e) {
            log.error("Error verifying GPG signature for commit {}", commit.getSha(), e);
            return false;
        }
    }

    private PGPPublicKey findPublicKey(long keyID) throws PGPException {
        if (trustedKeys == null) return null;
        Iterator<PGPPublicKeyRing> iter = trustedKeys.getKeyRings();
        while (iter.hasNext()) {
            PGPPublicKey key = iter.next().getPublicKey(keyID);
            if (key != null) return key;
        }
        return null;
    }

    private String createCommitDataString(Commit commit) {
        var sb = new StringBuilder();
        sb.append("tree TREE_HASH\n");
        if (commit.getParent() != null)
            sb.append("parent ").append(commit.getParent()).append("\n");
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

    private static PGPPublicKeyRingCollection loadTrustedKeys(GpgConfig config) {
        try {
            if (config.getTrustedKeysInline() != null
                    && !config.getTrustedKeysInline().isEmpty()) {
                return new PGPPublicKeyRingCollection(
                        PGPUtil.getDecoderStream(new ByteArrayInputStream(
                                config.getTrustedKeysInline().getBytes())),
                        new BcKeyFingerprintCalculator());
            }
            if (config.getTrustedKeysFile() != null
                    && !config.getTrustedKeysFile().isEmpty()) {
                File keyFile = new File(config.getTrustedKeysFile());
                if (keyFile.exists()) {
                    return new PGPPublicKeyRingCollection(
                            PGPUtil.getDecoderStream(new FileInputStream(keyFile)), new BcKeyFingerprintCalculator());
                }
            }
            return null;
        } catch (Exception e) {
            log.error("Error loading trusted GPG keys", e);
            return null;
        }
    }
}
