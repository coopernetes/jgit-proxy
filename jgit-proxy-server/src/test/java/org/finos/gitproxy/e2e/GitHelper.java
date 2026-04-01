package org.finos.gitproxy.e2e;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin wrapper around the system {@code git} CLI for use in e2e tests. Mirrors the pattern in the shell test scripts:
 * each helper method maps directly to a sequence of git commands that those scripts execute.
 */
class GitHelper {

    private final Path workDir;
    private String authorName;
    private String authorEmail;

    /** Creates a helper rooted at an existing directory. */
    GitHelper(Path workDir) {
        this.workDir = workDir;
    }

    /** Clones {@code remoteUrl} into a subdirectory named {@code dirName} under {@link #workDir}. */
    Path clone(String remoteUrl, String dirName) throws IOException, InterruptedException {
        Path dest = workDir.resolve(dirName);
        git(workDir, "clone", remoteUrl, dest.toString());
        return dest;
    }

    /**
     * Sets the local git author identity for subsequent commits made in {@code repoDir}. Git includes credentials in
     * the remote URL, so the author is the only identity that matters for validation purposes.
     */
    void setAuthor(Path repoDir, String name, String email) throws IOException, InterruptedException {
        this.authorName = name;
        this.authorEmail = email;
        git(repoDir, "config", "user.name", name);
        git(repoDir, "config", "user.email", email);
        // Disable GPG signing so tests don't need a keyring
        git(repoDir, "config", "commit.gpgSign", "false");
    }

    /** Writes {@code content} to {@code fileName} in {@code repoDir} and stages it. */
    void writeAndStage(Path repoDir, String fileName, String content) throws IOException, InterruptedException {
        Files.writeString(repoDir.resolve(fileName), content);
        git(repoDir, "add", fileName);
    }

    /** Creates a commit with {@code message} in {@code repoDir}. */
    void commit(Path repoDir, String message) throws IOException, InterruptedException {
        git(repoDir, "commit", "-m", message);
    }

    /**
     * Creates a new local branch at the current HEAD and checks it out. No new commits are made, so the branch tip is
     * identical to the source branch — useful for testing empty-branch rejection.
     */
    void createAndCheckoutBranch(Path repoDir, String branchName) throws IOException, InterruptedException {
        git(repoDir, "checkout", "-b", branchName);
    }

    /**
     * Attempts to push {@code repoDir} to its {@code origin} remote.
     *
     * @return {@code true} if the push exited 0, {@code false} otherwise.
     */
    boolean tryPush(Path repoDir) throws IOException, InterruptedException {
        String branch = currentBranch(repoDir);
        ProcessBuilder pb = buildGitCommand(repoDir, "push", "origin", branch);
        // Merge stdout+stderr so git's sideband messages are all captured
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // drain output (required so the process doesn't block on a full pipe)
        String output = new String(p.getInputStream().readAllBytes());
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            System.out.println("[GitHelper] push rejected: " + output);
        }
        return exitCode == 0;
    }

    /** Returns the captured combined stdout+stderr from a push that is expected to fail, for assertion purposes. */
    String pushOutput(Path repoDir) throws IOException, InterruptedException {
        String branch = currentBranch(repoDir);
        ProcessBuilder pb = buildGitCommand(repoDir, "push", "origin", branch);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return output;
    }

    private static final Pattern PUSH_ID_PATTERN =
            Pattern.compile("([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");

    /** Attempts a push and returns the result: exit code and combined output. */
    PushResult pushWithResult(Path repoDir) throws IOException, InterruptedException {
        String branch = currentBranch(repoDir);
        ProcessBuilder pb = buildGitCommand(repoDir, "push", "origin", branch);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        int exitCode = p.waitFor();
        return new PushResult(exitCode, output);
    }

    record PushResult(int exitCode, String output) {
        boolean succeeded() {
            return exitCode == 0;
        }

        /** Extracts the push ID from a "blocked pending review" error message. */
        String extractPushId() {
            Matcher m = PUSH_ID_PATTERN.matcher(output);
            if (!m.find()) {
                throw new AssertionError("No push ID found in output:\n" + output);
            }
            return m.group(1);
        }
    }

    // ---- private helpers ----

    private String currentBranch(Path repoDir) throws IOException, InterruptedException {
        ProcessBuilder pb = buildGitCommand(repoDir, "branch", "--show-current");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String branch = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return branch.isEmpty() ? "main" : branch;
    }

    private void git(Path dir, String... args) throws IOException, InterruptedException {
        ProcessBuilder pb = buildGitCommand(dir, args);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        if (code != 0) {
            throw new RuntimeException("git " + String.join(" ", args) + " failed (exit " + code + "): " + out);
        }
    }

    private ProcessBuilder buildGitCommand(Path dir, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_SSL_NO_VERIFY", "1");
        // Propagate author identity when set
        if (authorName != null) {
            pb.environment().put("GIT_AUTHOR_NAME", authorName);
            pb.environment().put("GIT_COMMITTER_NAME", authorName);
        }
        if (authorEmail != null) {
            pb.environment().put("GIT_AUTHOR_EMAIL", authorEmail);
            pb.environment().put("GIT_COMMITTER_EMAIL", authorEmail);
        }
        return pb;
    }
}
