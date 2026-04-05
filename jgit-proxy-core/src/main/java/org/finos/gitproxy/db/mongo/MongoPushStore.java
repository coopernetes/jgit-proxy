package org.finos.gitproxy.db.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MongoDB implementation of {@link PushStore}. Stores each push as a single document with embedded steps, commits, and
 * attestation for atomic reads/writes.
 */
public class MongoPushStore implements PushStore {

    private static final Logger log = LoggerFactory.getLogger(MongoPushStore.class);
    private static final String COLLECTION_NAME = "pushes";

    private final MongoClient mongoClient;
    private final MongoDatabase database;

    public MongoPushStore(MongoClient mongoClient, String databaseName) {
        this.mongoClient = mongoClient;
        this.database = mongoClient.getDatabase(databaseName);
    }

    @Override
    public void initialize() {
        MongoCollection<Document> collection = getCollection();
        collection.createIndex(Indexes.ascending("status"));
        collection.createIndex(Indexes.ascending("project"));
        collection.createIndex(Indexes.ascending("repoName"));
        collection.createIndex(Indexes.ascending("user"));
        collection.createIndex(Indexes.descending("timestamp"));
        log.info("MongoDB push store initialized");
    }

    @Override
    public void save(PushRecord record) {
        Document doc = toDocument(record);
        getCollection().insertOne(doc);
    }

    @Override
    public Optional<PushRecord> findById(String id) {
        Document doc = getCollection().find(Filters.eq("_id", id)).first();
        return Optional.ofNullable(doc).map(MongoPushStore::fromDocument);
    }

    @Override
    public List<PushRecord> find(PushQuery query) {
        List<Bson> filters = new ArrayList<>();

        if (query.getStatus() != null) {
            filters.add(Filters.eq("status", query.getStatus().name()));
        }
        if (query.getProject() != null) {
            filters.add(Filters.eq("project", query.getProject()));
        }
        if (query.getRepoName() != null) {
            filters.add(Filters.eq("repoName", query.getRepoName()));
        }
        if (query.getBranch() != null) {
            filters.add(Filters.eq("branch", query.getBranch()));
        }
        if (query.getUser() != null) {
            filters.add(Filters.eq("user", query.getUser()));
        }
        if (query.getAuthorEmail() != null) {
            filters.add(Filters.eq("authorEmail", query.getAuthorEmail()));
        }

        Bson filter = filters.isEmpty() ? new Document() : Filters.and(filters);
        Bson sort = query.isNewestFirst() ? Sorts.descending("timestamp") : Sorts.ascending("timestamp");

        List<PushRecord> results = new ArrayList<>();
        getCollection().find(filter).sort(sort).limit(query.getLimit()).forEach(doc -> results.add(fromDocument(doc)));
        return results;
    }

    @Override
    public void delete(String id) {
        getCollection().deleteOne(Filters.eq("_id", id));
    }

    @Override
    public PushRecord approve(String id, Attestation attestation) {
        return updateStatus(id, PushStatus.APPROVED, attestation);
    }

    @Override
    public PushRecord reject(String id, Attestation attestation) {
        return updateStatus(id, PushStatus.REJECTED, attestation);
    }

    @Override
    public PushRecord cancel(String id, Attestation attestation) {
        return updateStatus(id, PushStatus.CANCELED, attestation);
    }

    @Override
    public void updateForwardStatus(String id, PushStatus status, String errorMessage) {
        Document set = new Document("status", status.name()).append("forwardedAt", new java.util.Date());
        if (errorMessage != null) set.append("errorMessage", errorMessage);
        getCollection().updateOne(Filters.eq("_id", id), new Document("$set", set));
    }

    @Override
    public void close() {
        mongoClient.close();
    }

    // --- Private helpers ---

    private PushRecord updateStatus(String id, PushStatus status, Attestation attestation) {
        Document update = new Document("$set", new Document("status", status.name()));
        if (attestation != null) {
            update.get("$set", Document.class).append("attestation", attestationToDocument(attestation));
        }
        getCollection().updateOne(Filters.eq("_id", id), update);
        return findById(id).orElseThrow(() -> new IllegalArgumentException("Push not found: " + id));
    }

    private MongoCollection<Document> getCollection() {
        return database.getCollection(COLLECTION_NAME);
    }

    // --- Document mapping ---

    private static Document toDocument(PushRecord r) {
        Document doc = new Document("_id", r.getId())
                .append("timestamp", Date.from(r.getTimestamp()))
                .append("url", r.getUrl())
                .append("upstreamUrl", r.getUpstreamUrl())
                .append("project", r.getProject())
                .append("repoName", r.getRepoName())
                .append("branch", r.getBranch())
                .append("commitFrom", r.getCommitFrom())
                .append("commitTo", r.getCommitTo())
                .append("message", r.getMessage())
                .append("author", r.getAuthor())
                .append("authorEmail", r.getAuthorEmail())
                .append("committer", r.getCommitter())
                .append("committerEmail", r.getCommitterEmail())
                .append("user", r.getUser())
                .append("resolvedUser", r.getResolvedUser())
                .append("scmUsername", r.getScmUsername())
                .append("userEmail", r.getUserEmail())
                .append("method", r.getMethod())
                .append("status", r.getStatus().name())
                .append("errorMessage", r.getErrorMessage())
                .append("blockedMessage", r.getBlockedMessage())
                .append("autoApproved", r.isAutoApproved())
                .append("autoRejected", r.isAutoRejected())
                .append("forwardedAt", r.getForwardedAt() != null ? Date.from(r.getForwardedAt()) : null);

        if (r.getSteps() != null && !r.getSteps().isEmpty()) {
            doc.append(
                    "steps",
                    r.getSteps().stream().map(MongoPushStore::stepToDocument).toList());
        }
        if (r.getCommits() != null && !r.getCommits().isEmpty()) {
            doc.append(
                    "commits",
                    r.getCommits().stream()
                            .map(MongoPushStore::commitToDocument)
                            .toList());
        }
        if (r.getAttestation() != null) {
            doc.append("attestation", attestationToDocument(r.getAttestation()));
        }

        return doc;
    }

    private static PushRecord fromDocument(Document doc) {
        PushRecord.PushRecordBuilder builder = PushRecord.builder()
                .id(doc.getString("_id"))
                .timestamp(doc.getDate("timestamp").toInstant())
                .url(doc.getString("url"))
                .upstreamUrl(doc.getString("upstreamUrl"))
                .project(doc.getString("project"))
                .repoName(doc.getString("repoName"))
                .branch(doc.getString("branch"))
                .commitFrom(doc.getString("commitFrom"))
                .commitTo(doc.getString("commitTo"))
                .message(doc.getString("message"))
                .author(doc.getString("author"))
                .authorEmail(doc.getString("authorEmail"))
                .committer(doc.getString("committer"))
                .committerEmail(doc.getString("committerEmail"))
                .user(doc.getString("user"))
                .resolvedUser(doc.getString("resolvedUser"))
                .scmUsername(doc.getString("scmUsername"))
                .userEmail(doc.getString("userEmail"))
                .method(doc.getString("method"))
                .status(PushStatus.valueOf(doc.getString("status")))
                .errorMessage(doc.getString("errorMessage"))
                .blockedMessage(doc.getString("blockedMessage"))
                .autoApproved(doc.getBoolean("autoApproved", false))
                .autoRejected(doc.getBoolean("autoRejected", false))
                .forwardedAt(
                        doc.getDate("forwardedAt") != null
                                ? doc.getDate("forwardedAt").toInstant()
                                : null);

        List<Document> stepDocs = doc.getList("steps", Document.class);
        if (stepDocs != null) {
            builder.steps(stepDocs.stream()
                    .map(MongoPushStore::stepFromDocument)
                    .collect(ArrayList::new, List::add, List::addAll));
        }

        List<Document> commitDocs = doc.getList("commits", Document.class);
        if (commitDocs != null) {
            builder.commits(commitDocs.stream()
                    .map(MongoPushStore::commitFromDocument)
                    .collect(ArrayList::new, List::add, List::addAll));
        }

        Document attDoc = doc.get("attestation", Document.class);
        if (attDoc != null) {
            builder.attestation(attestationFromDocument(attDoc));
        }

        return builder.build();
    }

    private static Document stepToDocument(PushStep s) {
        return new Document("id", s.getId())
                .append("pushId", s.getPushId())
                .append("stepName", s.getStepName())
                .append("stepOrder", s.getStepOrder())
                .append("status", s.getStatus().name())
                .append("content", s.getContent())
                .append("errorMessage", s.getErrorMessage())
                .append("blockedMessage", s.getBlockedMessage())
                .append("logs", s.getLogs())
                .append("timestamp", Date.from(s.getTimestamp()));
    }

    private static PushStep stepFromDocument(Document doc) {
        return PushStep.builder()
                .id(doc.getString("id"))
                .pushId(doc.getString("pushId"))
                .stepName(doc.getString("stepName"))
                .stepOrder(doc.getInteger("stepOrder", 0))
                .status(StepStatus.valueOf(doc.getString("status")))
                .content(doc.getString("content"))
                .errorMessage(doc.getString("errorMessage"))
                .blockedMessage(doc.getString("blockedMessage"))
                .logs(doc.getList("logs", String.class, new ArrayList<>()))
                .timestamp(doc.getDate("timestamp").toInstant())
                .build();
    }

    private static Document commitToDocument(PushCommit c) {
        Document doc = new Document("sha", c.getSha())
                .append("parentSha", c.getParentSha())
                .append("authorName", c.getAuthorName())
                .append("authorEmail", c.getAuthorEmail())
                .append("committerName", c.getCommitterName())
                .append("committerEmail", c.getCommitterEmail())
                .append("message", c.getMessage())
                .append("signature", c.getSignature());
        if (c.getCommitDate() != null) {
            doc.append("commitDate", Date.from(c.getCommitDate()));
        }
        return doc;
    }

    private static PushCommit commitFromDocument(Document doc) {
        PushCommit.PushCommitBuilder builder = PushCommit.builder()
                .sha(doc.getString("sha"))
                .parentSha(doc.getString("parentSha"))
                .authorName(doc.getString("authorName"))
                .authorEmail(doc.getString("authorEmail"))
                .committerName(doc.getString("committerName"))
                .committerEmail(doc.getString("committerEmail"))
                .message(doc.getString("message"))
                .signature(doc.getString("signature"));
        Date commitDate = doc.getDate("commitDate");
        if (commitDate != null) {
            builder.commitDate(commitDate.toInstant());
        }
        return builder.build();
    }

    private static Document attestationToDocument(Attestation a) {
        return new Document("type", a.getType().name())
                .append("reviewerUsername", a.getReviewerUsername())
                .append("reviewerEmail", a.getReviewerEmail())
                .append("reason", a.getReason())
                .append("automated", a.isAutomated())
                .append("timestamp", Date.from(a.getTimestamp()));
    }

    private static Attestation attestationFromDocument(Document doc) {
        return Attestation.builder()
                .type(Attestation.Type.valueOf(doc.getString("type")))
                .reviewerUsername(doc.getString("reviewerUsername"))
                .reviewerEmail(doc.getString("reviewerEmail"))
                .reason(doc.getString("reason"))
                .automated(doc.getBoolean("automated", false))
                .timestamp(doc.getDate("timestamp").toInstant())
                .build();
    }
}
