package org.finos.gitproxy.db;

import java.util.List;
import org.finos.gitproxy.db.model.PushCommit;
import org.finos.gitproxy.db.model.PushRecord;
import org.finos.gitproxy.db.model.PushStatus;
import org.finos.gitproxy.git.Commit;
import org.finos.gitproxy.git.GitRequestDetails;

/** Maps between git domain objects and database persistence models. */
public final class PushRecordMapper {

    private PushRecordMapper() {}

    /** Convert a {@link GitRequestDetails} (from the proxy filter chain) to a {@link PushRecord}. */
    public static PushRecord fromRequestDetails(GitRequestDetails details) {
        PushRecord.PushRecordBuilder builder = PushRecord.builder()
                .id(details.getId().toString())
                .timestamp(details.getTimestamp())
                .branch(details.getBranch())
                .commitFrom(details.getCommitFrom())
                .commitTo(details.getCommitTo())
                .method(details.getOperation() != null ? details.getOperation().name() : null)
                .status(mapResult(details.getResult()));

        if (details.getReason() != null) {
            PushStatus status = mapResult(details.getResult());
            if (status == PushStatus.ERROR) {
                builder.errorMessage(details.getReason());
            } else if (status == PushStatus.BLOCKED) {
                builder.blockedMessage(details.getReason());
            }
        }

        if (details.getRepository() != null) {
            builder.url(details.getRepository().getSlug());
            builder.project(details.getRepository().getOwner());
            builder.repoName(details.getRepository().getName());
        }

        if (details.getProvider() != null) {
            String url = builder.build().getUrl();
            if (url == null) {
                builder.url(details.getProvider().getName());
            }
            // Construct the upstream URL from provider URI + repo slug
            String providerUri = details.getProvider().getUri().toString();
            String slug = details.getRepository() != null ? details.getRepository().getSlug() : null;
            if (slug != null) {
                String upstreamUrl = providerUri.endsWith("/") ? providerUri + slug : providerUri + "/" + slug;
                builder.upstreamUrl(upstreamUrl);
            }
        }

        // Map commit author info from the head commit
        if (details.getCommit() != null) {
            Commit head = details.getCommit();
            if (head.getAuthor() != null) {
                builder.author(head.getAuthor().getName());
                builder.authorEmail(head.getAuthor().getEmail());
            }
        }

        // Map pushed commits
        if (details.getPushedCommits() != null && !details.getPushedCommits().isEmpty()) {
            String pushId = details.getId().toString();
            List<PushCommit> commits = details.getPushedCommits().stream()
                    .map(c -> mapCommit(pushId, c))
                    .toList();
            builder.commits(new java.util.ArrayList<>(commits));
        }

        // Map filter steps (already PushStep objects, just ensure pushId is set)
        if (details.getSteps() != null && !details.getSteps().isEmpty()) {
            String pushId = details.getId().toString();
            details.getSteps().forEach(s -> s.setPushId(pushId));
            builder.steps(new java.util.ArrayList<>(details.getSteps()));
        }

        return builder.build();
    }

    /** Convert a git {@link Commit} to a {@link PushCommit}. */
    public static PushCommit mapCommit(String pushId, Commit commit) {
        PushCommit.PushCommitBuilder builder =
                PushCommit.builder().pushId(pushId).sha(commit.getSha()).message(commit.getMessage());

        if (commit.getParent() != null) {
            builder.parentSha(commit.getParent());
        }
        if (commit.getAuthor() != null) {
            builder.authorName(commit.getAuthor().getName());
            builder.authorEmail(commit.getAuthor().getEmail());
        }
        if (commit.getCommitter() != null) {
            builder.committerName(commit.getCommitter().getName());
            builder.committerEmail(commit.getCommitter().getEmail());
        }
        if (commit.getDate() != null) {
            builder.commitDate(commit.getDate());
        }
        if (commit.getSignature() != null) {
            builder.signature(commit.getSignature());
        }

        return builder.build();
    }

    /** Map {@link GitRequestDetails.GitResult} to {@link PushStatus}. */
    public static PushStatus mapResult(GitRequestDetails.GitResult result) {
        if (result == null) return PushStatus.RECEIVED;
        return switch (result) {
            case PENDING -> PushStatus.PROCESSING;
            case ALLOWED -> PushStatus.APPROVED;
            case BLOCKED -> PushStatus.BLOCKED;
            case ACCEPTED -> PushStatus.RECEIVED;
            case ERROR -> PushStatus.ERROR;
        };
    }
}
