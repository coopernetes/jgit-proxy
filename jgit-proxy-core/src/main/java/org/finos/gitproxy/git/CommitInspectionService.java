package org.finos.gitproxy.git;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

/**
 * Service for extracting commit information from a JGit repository. Provides utilities to get commit details, diffs,
 * and other git data using JGit primitives.
 */
@Slf4j
public class CommitInspectionService {

    /**
     * Extract commit details from a repository.
     *
     * @param repository The JGit repository
     * @param commitId The commit ID (SHA)
     * @return The commit details
     * @throws IOException If git operations fail
     */
    public static Commit getCommitDetails(Repository repository, String commitId) throws IOException {
        try (RevWalk revWalk = new RevWalk(repository)) {
            ObjectId objectId = repository.resolve(commitId);
            if (objectId == null) {
                throw new IOException("Commit not found: " + commitId);
            }

            RevCommit revCommit = revWalk.parseCommit(objectId);
            return convertToCommit(revCommit);
        }
    }

    /**
     * Get a range of commits between two commit IDs.
     *
     * @param repository The JGit repository
     * @param fromCommit The starting commit (exclusive)
     * @param toCommit The ending commit (inclusive)
     * @return List of commits in the range
     * @throws IOException If git operations fail
     * @throws GitAPIException If git API operations fail
     */
    public static List<Commit> getCommitRange(Repository repository, String fromCommit, String toCommit)
            throws IOException, GitAPIException {
        List<Commit> commits = new ArrayList<>();

        try (Git git = new Git(repository)) {
            ObjectId fromId = repository.resolve(fromCommit);
            ObjectId toId = repository.resolve(toCommit);

            if (toId == null) {
                throw new IOException("Commit not found: " + toCommit);
            }

            // Get commits from toCommit back to (but not including) fromCommit
            Iterable<RevCommit> revCommits;
            if (fromId != null && !isNullCommit(fromCommit)) {
                revCommits = git.log().addRange(fromId, toId).call();
            } else {
                // New branch - get all commits up to toCommit
                revCommits = git.log().add(toId).call();
            }

            for (RevCommit revCommit : revCommits) {
                commits.add(convertToCommit(revCommit));
            }
        }

        return commits;
    }

    /**
     * Get the diff between two commits.
     *
     * @param repository The JGit repository
     * @param fromCommit The starting commit
     * @param toCommit The ending commit
     * @return List of diff entries
     * @throws IOException If git operations fail
     */
    public static List<DiffEntry> getDiff(Repository repository, String fromCommit, String toCommit)
            throws IOException {
        try (ObjectReader reader = repository.newObjectReader()) {
            CanonicalTreeParser oldTreeParser = new CanonicalTreeParser();
            CanonicalTreeParser newTreeParser = new CanonicalTreeParser();

            ObjectId oldId = repository.resolve(fromCommit + "^{tree}");
            ObjectId newId = repository.resolve(toCommit + "^{tree}");

            if (oldId != null) {
                oldTreeParser.reset(reader, oldId);
            }
            if (newId != null) {
                newTreeParser.reset(reader, newId);
            }

            try (Git git = new Git(repository)) {
                return git.diff()
                        .setOldTree(oldTreeParser)
                        .setNewTree(newTreeParser)
                        .call();
            } catch (GitAPIException e) {
                throw new IOException("Failed to get diff", e);
            }
        }
    }

    /**
     * Get a formatted diff string between two commits.
     *
     * @param repository The JGit repository
     * @param fromCommit The starting commit
     * @param toCommit The ending commit
     * @return The formatted diff as a string
     * @throws IOException If git operations fail
     */
    public static String getFormattedDiff(Repository repository, String fromCommit, String toCommit)
            throws IOException {
        List<DiffEntry> diffs = getDiff(repository, fromCommit, toCommit);

        StringBuilder diffText = new StringBuilder();
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                DiffFormatter formatter = new DiffFormatter(out)) {
            formatter.setRepository(repository);
            for (DiffEntry diff : diffs) {
                formatter.format(diff);
                diffText.append(out.toString());
                out.reset();
            }
        }

        return diffText.toString();
    }

    /**
     * Check if a commit ID represents a null commit (all zeros).
     *
     * @param commitId The commit ID to check
     * @return true if the commit is a null commit
     */
    private static boolean isNullCommit(String commitId) {
        return commitId == null || commitId.matches("^0+$");
    }

    /**
     * Convert a JGit RevCommit to our Commit model.
     *
     * @param revCommit The JGit commit
     * @return Our Commit model
     */
    private static Commit convertToCommit(RevCommit revCommit) {
        PersonIdent author = revCommit.getAuthorIdent();
        PersonIdent committer = revCommit.getCommitterIdent();

        String parentSha = null;
        if (revCommit.getParentCount() > 0) {
            parentSha = revCommit.getParent(0).getName();
        }

        // Extract GPG signature if present
        // Note: Signature extraction is handled by GitReceivePackParser during push parsing
        // and is available in the Commit object passed from the push packet data
        String signature = null;

        return Commit.builder()
                .sha(revCommit.getName())
                .parent(parentSha)
                .author(Contributor.builder()
                        .name(author.getName())
                        .email(author.getEmailAddress())
                        .build())
                .committer(Contributor.builder()
                        .name(committer.getName())
                        .email(committer.getEmailAddress())
                        .build())
                .message(revCommit.getFullMessage())
                .date(Instant.ofEpochSecond(committer.getWhen().getTime() / 1000))
                .signature(signature)
                .build();
    }
}
