package org.finos.gitproxy.git;

import static org.finos.gitproxy.git.GitClientUtils.ZERO_OID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.config.CommitConfig;

/**
 * Runs the gitleaks secret scanner against a unified diff and returns structured findings.
 *
 * <p>Binary resolution order (first match wins):
 *
 * <ol>
 *   <li>{@code SecretScanningConfig.scannerPath} — explicit path, bypasses all other resolution
 *   <li>Version-pinned download — if {@code version} is set and {@code autoInstall} is {@code true}, downloads that
 *       specific gitleaks release from GitHub and caches it in {@code installDir}; use this to pin a version different
 *       from the one bundled in the JAR
 *   <li>Bundled JAR binary — the gitleaks binary packaged into the JAR at build time ({@link #DEFAULT_VERSION}); always
 *       present in standard builds
 *   <li>System PATH — falls back to a {@code gitleaks} binary already installed on the host/container
 * </ol>
 *
 * <p>If no binary is found, scanning is skipped and an empty {@link Optional} is returned (fail-open). Pushes are never
 * blocked due to scanner unavailability.
 *
 * <p>The bundled binary extracted from the JAR is deleted on JVM shutdown. Binaries downloaded to {@code installDir}
 * persist across restarts.
 */
@Slf4j
public class GitleaksRunner {

    /**
     * Default gitleaks version used for auto-install. Keep in sync with {@code gitleaksVersion} in
     * {@code jgit-proxy-core/build.gradle}.
     */
    public static final String DEFAULT_VERSION = "8.21.2";

    /** Classpath resource name for the pre-bundled gitleaks binary (opt-in, not shipped by default). */
    private static final String BUNDLED_BINARY_RESOURCE = "gitleaks";

    private static final String DEFAULT_INSTALL_DIR = System.getProperty("user.home") + "/.cache/jgit-proxy/gitleaks";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Lazily extracted classpath binary — shared across all instances (deleted on JVM exit). */
    private static volatile Path extractedBinaryPath;

    private static final Object EXTRACT_LOCK = new Object();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Scans the provided unified diff for secrets using gitleaks.
     *
     * @param diff unified diff text to scan (may be empty)
     * @param config secret scanning configuration
     * @return {@link Optional#empty()} if the scanner is unavailable or errored (fail-open); otherwise an optional
     *     containing the (possibly empty) list of findings
     */
    public Optional<List<Finding>> scan(String diff, CommitConfig.SecretScanningConfig config) {
        if (diff == null || diff.isBlank()) {
            return Optional.of(Collections.emptyList());
        }

        Path binaryPath = resolveBinaryPath(config);
        if (binaryPath == null) {
            log.warn("gitleaks binary not available — secret scanning skipped (fail-open). "
                    + "Set commit.secretScanning.auto-install: true or provide scanner-path.");
            return Optional.empty();
        }

        Path reportFile = null;
        try {
            reportFile = Files.createTempFile("gitleaks-report-", ".json");
            List<String> cmd = buildCommand(binaryPath, reportFile, config);
            log.debug("Running gitleaks: {}", cmd);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            // Run in /tmp so gitleaks doesn't walk the server's working directory
            pb.directory(reportFile.getParent().toFile());
            Process process = pb.start();

            // Write diff to stdin in a daemon thread; closing stdin signals EOF to gitleaks
            final byte[] diffBytes = diff.getBytes(StandardCharsets.UTF_8);
            Thread stdinWriter = new Thread(() -> {
                try (var stdin = process.getOutputStream()) {
                    stdin.write(diffBytes);
                } catch (IOException e) {
                    log.debug("stdin write error (process may have exited early)", e);
                }
            });
            stdinWriter.setDaemon(true);
            stdinWriter.start();

            boolean completed = process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                stdinWriter.interrupt();
                log.warn(
                        "gitleaks timed out after {}s — secret scanning skipped (fail-open)",
                        config.getTimeoutSeconds());
                return Optional.empty();
            }

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.debug("gitleaks: no findings");
                return Optional.of(Collections.emptyList());
            } else if (exitCode == 1) {
                List<Finding> findings = readFindings(reportFile);
                enrichFindings(findings, diff);
                log.debug("gitleaks: {} finding(s)", findings.size());
                return Optional.of(findings);
            } else {
                log.warn("gitleaks exited with code {} — secret scanning skipped (fail-open)", exitCode);
                return Optional.empty();
            }

        } catch (Exception e) {
            log.warn("Failed to run gitleaks — secret scanning skipped (fail-open): {}", e.getMessage(), e);
            return Optional.empty();
        } finally {
            if (reportFile != null) {
                try {
                    Files.deleteIfExists(reportFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Scans a commit range in a local git repository for secrets using {@code gitleaks git}.
     *
     * <p>Unlike {@link #scan(String, CommitConfig.SecretScanningConfig)}, this mode runs gitleaks natively against the
     * git object graph, so path-based allowlists and per-file context in gitleaks rules are applied correctly. No
     * post-hoc diff enrichment is needed — gitleaks populates {@code File}, {@code StartLine}, and {@code Commit} in
     * each finding directly.
     *
     * <p>For a new-branch push ({@code commitFrom} equals {@link GitClientUtils#ZERO_OID }), the scan covers all
     * commits reachable from {@code commitTo} that are not yet reachable from any existing ref ({@code --not --all}).
     * For a branch update, the scan covers {@code commitFrom..commitTo}.
     *
     * @param repoDir path to the git repository root (bare or non-bare)
     * @param commitFrom old-tip OID; use {@link GitClientUtils#ZERO_OID} for a new-branch push
     * @param commitTo new-tip OID
     * @param config secret scanning configuration
     * @return {@link Optional#empty()} if the scanner is unavailable or errored (fail-open); otherwise the (possibly
     *     empty) list of findings
     */
    public Optional<List<Finding>> scanGit(
            Path repoDir, String commitFrom, String commitTo, CommitConfig.SecretScanningConfig config) {
        Path binaryPath = resolveBinaryPath(config);
        if (binaryPath == null) {
            log.warn("gitleaks binary not available — secret scanning skipped (fail-open). "
                    + "Set commit.secretScanning.auto-install: true or provide scanner-path.");
            return Optional.empty();
        }

        // New-branch push: scan only commits not reachable from any existing ref.
        // Branch update: scan only the new commits introduced by this push.
        String logOpts = ZERO_OID.equals(commitFrom) ? commitTo + " --not --all" : commitFrom + ".." + commitTo;

        Path reportFile = null;
        try {
            reportFile = Files.createTempFile("gitleaks-report-", ".json");
            List<String> cmd = buildGitCommand(binaryPath, logOpts, reportFile, config);
            log.debug("Running gitleaks git: {}", cmd);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            // Run inside the repo so gitleaks can traverse the git object graph
            pb.directory(repoDir.toFile());
            Process process = pb.start();

            // Gitleaks git writes findings to the JSON report file; drain output to avoid blocking
            process.getInputStream().transferTo(OutputStream.nullOutputStream());
            process.getErrorStream().transferTo(OutputStream.nullOutputStream());

            boolean completed = process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                log.warn(
                        "gitleaks timed out after {}s — secret scanning skipped (fail-open)",
                        config.getTimeoutSeconds());
                return Optional.empty();
            }

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.debug("gitleaks git: no findings");
                return Optional.of(Collections.emptyList());
            } else if (exitCode == 1) {
                List<Finding> findings = readFindings(reportFile);
                log.debug("gitleaks git: {} finding(s)", findings.size());
                return Optional.of(findings);
            } else {
                log.warn("gitleaks git exited with code {} — secret scanning skipped (fail-open)", exitCode);
                return Optional.empty();
            }

        } catch (Exception e) {
            log.warn("Failed to run gitleaks git — secret scanning skipped (fail-open): {}", e.getMessage(), e);
            return Optional.empty();
        } finally {
            if (reportFile != null) {
                try {
                    Files.deleteIfExists(reportFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Binary resolution
    // -------------------------------------------------------------------------

    private Path resolveBinaryPath(CommitConfig.SecretScanningConfig config) {
        // 1. Explicit scanner-path — always wins
        if (config.getScannerPath() != null && !config.getScannerPath().isBlank()) {
            log.debug("gitleaks: using configured scanner-path={}", config.getScannerPath());
            return Path.of(config.getScannerPath());
        }

        // 2. Version-pinned download — only when caller requests a specific version via auto-install
        if (config.isAutoInstall()
                && config.getVersion() != null
                && !config.getVersion().isBlank()) {
            Path installDir = resolveInstallDir(config);
            Path cached = installDir.resolve("gitleaks-" + config.getVersion());
            if (Files.isExecutable(cached)) {
                log.debug("gitleaks: using cached version {} at {}", config.getVersion(), cached);
                return cached;
            }
            Path downloaded = autoInstall(config, installDir);
            if (downloaded != null) return downloaded;
        }

        // 3. Bundled JAR binary (DEFAULT_VERSION, always present in standard builds)
        try {
            Path bundled = extractBundledBinary();
            if (bundled != null) return bundled;
        } catch (IOException e) {
            log.debug("Failed to extract bundled gitleaks binary: {}", e.getMessage());
        }

        // 4. System PATH
        Path onPath = findOnPath();
        if (onPath != null) {
            log.debug("gitleaks: found on system PATH");
            return onPath;
        }

        return null;
    }

    /** Returns {@code Path.of("gitleaks")} if gitleaks is available on the system PATH, else null. */
    private static Path findOnPath() {
        try {
            Process p = new ProcessBuilder("gitleaks", "version")
                    .redirectErrorStream(true)
                    .start();
            // Drain output to avoid blocking
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                return Path.of("gitleaks");
            }
        } catch (Exception ignored) {
            // Not on PATH
        }
        return null;
    }

    private static Path resolveInstallDir(CommitConfig.SecretScanningConfig config) {
        String dir = config.getInstallDir();
        if (dir != null && !dir.isBlank()) {
            return Path.of(dir.replace("~", System.getProperty("user.home")));
        }
        return Path.of(DEFAULT_INSTALL_DIR);
    }

    private Path autoInstall(CommitConfig.SecretScanningConfig config, Path installDir) {
        String version =
                (config.getVersion() != null && !config.getVersion().isBlank()) ? config.getVersion() : DEFAULT_VERSION;

        String tarSuffix = detectTarSuffix();
        if (tarSuffix == null) {
            log.warn(
                    "Cannot auto-install gitleaks: unsupported platform (os={}, arch={}). "
                            + "Install gitleaks manually and set commit.secretScanning.scanner-path.",
                    System.getProperty("os.name"),
                    System.getProperty("os.arch"));
            return null;
        }

        String tarName = "gitleaks_" + version + "_" + tarSuffix + ".tar.gz";
        String downloadUrl = "https://github.com/gitleaks/gitleaks/releases/download/v" + version + "/" + tarName;
        // Name the binary with the version so different versions can coexist in the cache dir
        Path binary = installDir.resolve("gitleaks-" + version);

        try {
            Files.createDirectories(installDir);
            Path tarFile = installDir.resolve(tarName);

            log.info("Auto-installing gitleaks {} to {} ...", version, installDir);
            try (InputStream in = URI.create(downloadUrl).toURL().openStream()) {
                Files.copy(in, tarFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            // Extract with Ant-style untar via the JVM's built-in zip/tar support isn't available,
            // so shell out to tar which is present on all Unix systems
            Process tar = new ProcessBuilder("tar", "-xzf", tarFile.toString(), "-C", installDir.toString(), "gitleaks")
                    .redirectErrorStream(true)
                    .start();
            String tarOutput = new String(tar.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!tar.waitFor(60, TimeUnit.SECONDS) || tar.exitValue() != 0) {
                log.warn("Failed to extract gitleaks tarball: {}", tarOutput);
                return null;
            }

            Files.deleteIfExists(tarFile);

            // tar extracts the binary as plain "gitleaks"; rename to versioned name
            Path extracted = installDir.resolve("gitleaks");
            if (!binary.equals(extracted) && Files.exists(extracted)) {
                Files.move(extracted, binary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            makeExecutable(binary);
            log.info("gitleaks {} installed at {}", version, binary);
            return binary;

        } catch (Exception e) {
            log.warn(
                    "Failed to auto-install gitleaks {} — secret scanning skipped (fail-open): {}",
                    version,
                    e.getMessage());
            return null;
        }
    }

    /** Detects the current platform and returns the gitleaks tarball suffix, or null if unsupported. */
    static String detectTarSuffix() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();

        String osKey = os.contains("linux") ? "linux" : (os.contains("mac") || os.contains("darwin")) ? "darwin" : null;
        String archKey = (arch.contains("amd64") || arch.contains("x86_64"))
                ? "x64"
                : (arch.contains("aarch64") || arch.contains("arm64")) ? "arm64" : null;

        return (osKey != null && archKey != null) ? osKey + "_" + archKey : null;
    }

    private static void makeExecutable(Path path) throws IOException {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException e) {
            path.toFile().setExecutable(true);
        }
    }

    private Path extractBundledBinary() throws IOException {
        if (extractedBinaryPath != null) return extractedBinaryPath;
        synchronized (EXTRACT_LOCK) {
            if (extractedBinaryPath != null) return extractedBinaryPath;

            InputStream resource = GitleaksRunner.class.getClassLoader().getResourceAsStream(BUNDLED_BINARY_RESOURCE);
            if (resource == null) return null;

            Path tempDir = Files.createTempDirectory("jgit-proxy-gitleaks-");
            Path binaryPath = tempDir.resolve("gitleaks");
            try (resource) {
                Files.copy(resource, binaryPath);
            }
            makeExecutable(binaryPath);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.deleteIfExists(binaryPath);
                    Files.deleteIfExists(tempDir);
                } catch (IOException ignored) {
                }
            }));

            extractedBinaryPath = binaryPath;
            log.info("Extracted bundled gitleaks binary to {}", binaryPath);
            return extractedBinaryPath;
        }
    }

    // -------------------------------------------------------------------------
    // Command building
    // -------------------------------------------------------------------------

    private static List<String> buildCommand(
            Path binaryPath, Path reportFile, CommitConfig.SecretScanningConfig config) {
        List<String> cmd = new ArrayList<>();
        cmd.add(binaryPath.toString());
        cmd.add("detect");
        cmd.add("--pipe");
        cmd.add("--report-format");
        cmd.add("json");
        cmd.add("--report-path");
        cmd.add(reportFile.toString());

        if (config.getConfigFile() != null && !config.getConfigFile().isBlank()) {
            cmd.add("--config");
            cmd.add(config.getConfigFile());
        }

        return cmd;
    }

    private static List<String> buildGitCommand(
            Path binaryPath, String logOpts, Path reportFile, CommitConfig.SecretScanningConfig config) {
        List<String> cmd = new ArrayList<>();
        cmd.add(binaryPath.toString());
        cmd.add("git");
        cmd.add("--log-opts=" + logOpts);
        cmd.add("--report-format");
        cmd.add("json");
        cmd.add("--report-path");
        cmd.add(reportFile.toString());
        cmd.add("--no-banner");
        cmd.add("--redact");

        if (config.getConfigFile() != null && !config.getConfigFile().isBlank()) {
            cmd.add("--config");
            cmd.add(config.getConfigFile());
        }

        return cmd;
    }

    // -------------------------------------------------------------------------
    // JSON parsing
    // -------------------------------------------------------------------------

    private static List<Finding> readFindings(Path reportFile) {
        try {
            if (!Files.exists(reportFile) || Files.size(reportFile) == 0) {
                return Collections.emptyList();
            }
            String json = Files.readString(reportFile);
            if (json.isBlank() || json.trim().equals("null")) {
                return Collections.emptyList();
            }
            return OBJECT_MAPPER.readValue(json, new TypeReference<List<Finding>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse gitleaks JSON report: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // -------------------------------------------------------------------------
    // Diff-line enrichment
    // -------------------------------------------------------------------------

    /**
     * Gitleaks {@code --pipe} mode reports {@code StartLine} as the 0-indexed line number within the raw diff text and
     * leaves {@code File} empty. This method parses the diff to map each finding back to the actual file path and the
     * file-relative (1-indexed) line number.
     */
    private static void enrichFindings(List<Finding> findings, String diff) {
        if (findings.isEmpty()) return;

        String[] lines = diff.split("\n", -1);
        String[] fileAtLine = new String[lines.length];
        int[] fileLineAtLine = new int[lines.length];

        String currentFile = null;
        int nextFileLine = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("+++ b/")) {
                currentFile = line.substring("+++ b/".length());
            } else if (line.startsWith("+++ /dev/null")) {
                currentFile = null;
            } else if (line.startsWith("@@")) {
                nextFileLine = parseHunkNewStart(line);
            } else if (line.startsWith("+")) {
                fileAtLine[i] = currentFile;
                fileLineAtLine[i] = nextFileLine;
                nextFileLine++;
            } else if (line.startsWith(" ")) {
                nextFileLine++;
            }
        }

        for (Finding f : findings) {
            int idx = f.getStartLine(); // 0-indexed line in diff
            if (idx >= 0 && idx < lines.length && fileAtLine[idx] != null) {
                f.setFile(fileAtLine[idx]);
                f.setStartLine(fileLineAtLine[idx]);
            }
        }
    }

    private static int parseHunkNewStart(String hunkHeader) {
        // "@@ -old_start[,old_count] +new_start[,new_count] @@"
        int plusIdx = hunkHeader.indexOf(" +");
        if (plusIdx < 0) return 1;
        int start = plusIdx + 2;
        int end = hunkHeader.indexOf(',', start);
        if (end < 0) end = hunkHeader.indexOf(' ', start);
        if (end < 0) return 1;
        try {
            return Integer.parseInt(hunkHeader.substring(start, end));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    // -------------------------------------------------------------------------
    // Finding model
    // -------------------------------------------------------------------------

    /** A single secret detected by gitleaks. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Finding {

        @JsonProperty("Description")
        private String description;

        @JsonProperty("RuleID")
        private String ruleId;

        @JsonProperty("File")
        private String file;

        @JsonProperty("StartLine")
        private int startLine;

        @JsonProperty("Commit")
        private String commit;

        /**
         * The matched text with the actual secret value partially redacted by gitleaks (the {@code Match} field) rather
         * than the raw {@code Secret} field, to avoid logging plaintext secrets.
         */
        @JsonProperty("Match")
        private String match;

        /**
         * Multi-line summary suitable for a push error message. Each line is kept short to fit the git sideband 80-char
         * width limit (git prefixes each newline with "remote: ").
         */
        public String toMessage() {
            StringBuilder sb = new StringBuilder();

            // Line 1: rule ID + location
            sb.append("[").append(ruleId != null ? ruleId : "unknown").append("]");
            if (file != null && !file.isBlank()) {
                sb.append("  ").append(file);
                if (startLine > 0) {
                    sb.append(":").append(startLine);
                }
            }

            // Line 2: commit hash (short)
            if (commit != null && commit.length() >= 7) {
                sb.append("\n  commit: ").append(commit, 0, 7);
            }

            // Line 3: redacted match snippet
            if (match != null && !match.isBlank()) {
                sb.append("\n  match:  ").append(match);
            }

            return sb.toString();
        }
    }
}
