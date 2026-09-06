package com.rbc.fogwall.db.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.rbc.fogwall.db.ScmApiActionStore;
import com.rbc.fogwall.db.model.ScmApiActionQuery;
import com.rbc.fogwall.db.model.ScmApiActionRecord;
import com.rbc.fogwall.db.model.ScmApiActionStatus;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.bson.Document;
import org.bson.conversions.Bson;

/** MongoDB-based {@link ScmApiActionStore} implementation. */
public class MongoScmApiActionStore implements ScmApiActionStore {

    private static final String COLLECTION_NAME = "scm_api_action_records";

    private final MongoDatabase database;

    public MongoScmApiActionStore(MongoClient mongoClient, String databaseName) {
        this.database = mongoClient.getDatabase(databaseName);
    }

    @Override
    public void initialize() {
        MongoCollection<Document> col = getCollection();
        col.createIndex(Indexes.ascending("resolved_user"));
        col.createIndex(Indexes.descending("timestamp"));
    }

    @Override
    public void save(ScmApiActionRecord r) {
        Document doc = new Document("_id", r.getId())
                .append("timestamp", Date.from(r.getTimestamp()))
                .append("provider", r.getProvider())
                .append("scm_username", r.getScmUsername())
                .append("resolved_user", r.getResolvedUser())
                .append("repo_owner", r.getRepoOwner())
                .append("repo_name", r.getRepoName())
                .append("mutation_field", r.getMutationField())
                .append("node_id", r.getNodeId())
                .append("node_type", r.getNodeType())
                .append("status", r.getStatus().name())
                .append("reason", r.getReason())
                .append("variables_json", r.getVariablesJson())
                .append("user_agent", r.getUserAgent())
                .append("client_type", r.getClientType())
                .append("client_version", r.getClientVersion());
        getCollection().insertOne(doc);
    }

    @Override
    public Optional<ScmApiActionRecord> findById(String id) {
        Document doc = getCollection().find(Filters.eq("_id", id)).first();
        return Optional.ofNullable(doc).map(MongoScmApiActionStore::toRecord);
    }

    @Override
    public List<ScmApiActionRecord> find(ScmApiActionQuery query) {
        List<Bson> filters = new ArrayList<>();

        if (query.getStatus() != null) {
            filters.add(Filters.eq("status", query.getStatus().name()));
        }
        if (query.getProvider() != null) {
            filters.add(Filters.eq("provider", query.getProvider()));
        }
        if (query.getUser() != null) {
            filters.add(Filters.eq("resolved_user", query.getUser()));
        }
        if (query.getRepoOwner() != null) {
            filters.add(Filters.eq("repo_owner", query.getRepoOwner()));
        }
        if (query.getRepoName() != null) {
            filters.add(Filters.eq("repo_name", query.getRepoName()));
        }
        if (query.getSearch() != null && !query.getSearch().isBlank()) {
            String pattern = "(?i).*" + Pattern.quote(query.getSearch()) + ".*";
            filters.add(Filters.or(Filters.regex("repo_owner", pattern), Filters.regex("repo_name", pattern)));
        }

        Bson filter = filters.isEmpty() ? new Document() : Filters.and(filters);
        Bson sort = query.isNewestFirst() ? Sorts.descending("timestamp") : Sorts.ascending("timestamp");

        List<ScmApiActionRecord> results = new ArrayList<>();
        getCollection()
                .find(filter)
                .sort(sort)
                .skip(query.getOffset())
                .limit(query.getLimit())
                .forEach(doc -> results.add(toRecord(doc)));
        return results;
    }

    private static ScmApiActionRecord toRecord(Document doc) {
        return ScmApiActionRecord.builder()
                .id(doc.getString("_id"))
                .timestamp(doc.getDate("timestamp").toInstant())
                .provider(doc.getString("provider"))
                .scmUsername(doc.getString("scm_username"))
                .resolvedUser(doc.getString("resolved_user"))
                .repoOwner(doc.getString("repo_owner"))
                .repoName(doc.getString("repo_name"))
                .mutationField(doc.getString("mutation_field"))
                .nodeId(doc.getString("node_id"))
                .nodeType(doc.getString("node_type"))
                .status(ScmApiActionStatus.valueOf(doc.getString("status")))
                .reason(doc.getString("reason"))
                .variablesJson(doc.getString("variables_json"))
                .userAgent(doc.getString("user_agent"))
                .clientType(doc.getString("client_type"))
                .clientVersion(doc.getString("client_version"))
                .build();
    }

    private MongoCollection<Document> getCollection() {
        return database.getCollection(COLLECTION_NAME);
    }
}
