package org.finos.gitproxy.permission;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MongoDB implementation of {@link RepoPermissionStore}. */
public class MongoRepoPermissionStore implements RepoPermissionStore {

    private static final Logger log = LoggerFactory.getLogger(MongoRepoPermissionStore.class);
    private static final String COLLECTION_NAME = "repo_permissions";

    private final MongoDatabase database;

    public MongoRepoPermissionStore(MongoClient mongoClient, String databaseName) {
        this.database = mongoClient.getDatabase(databaseName);
    }

    @Override
    public void initialize() {
        MongoCollection<Document> col = getCollection();
        col.createIndex(Indexes.ascending("username"));
        col.createIndex(Indexes.ascending("provider"));
        log.info("MongoDB repo permission store initialized");
    }

    @Override
    public void save(RepoPermission p) {
        getCollection().insertOne(toDocument(p));
    }

    @Override
    public void delete(String id) {
        getCollection().deleteOne(Filters.eq("_id", id));
    }

    @Override
    public Optional<RepoPermission> findById(String id) {
        Document doc = getCollection().find(Filters.eq("_id", id)).first();
        return Optional.ofNullable(doc).map(MongoRepoPermissionStore::fromDocument);
    }

    @Override
    public List<RepoPermission> findAll() {
        List<RepoPermission> results = new ArrayList<>();
        getCollection()
                .find()
                .sort(Sorts.ascending("provider", "path", "username"))
                .forEach(doc -> results.add(fromDocument(doc)));
        return results;
    }

    @Override
    public List<RepoPermission> findByUsername(String username) {
        List<RepoPermission> results = new ArrayList<>();
        getCollection()
                .find(Filters.eq("username", username))
                .sort(Sorts.ascending("provider", "path"))
                .forEach(doc -> results.add(fromDocument(doc)));
        return results;
    }

    @Override
    public List<RepoPermission> findByProvider(String provider) {
        List<RepoPermission> results = new ArrayList<>();
        getCollection()
                .find(Filters.eq("provider", provider))
                .sort(Sorts.ascending("path", "username"))
                .forEach(doc -> results.add(fromDocument(doc)));
        return results;
    }

    private MongoCollection<Document> getCollection() {
        return database.getCollection(COLLECTION_NAME);
    }

    private static Document toDocument(RepoPermission p) {
        return new Document("_id", p.getId())
                .append("username", p.getUsername())
                .append("provider", p.getProvider())
                .append("path", p.getPath())
                .append("pathType", p.getPathType().name())
                .append("operations", p.getOperations().name())
                .append("source", p.getSource().name());
    }

    private static RepoPermission fromDocument(Document doc) {
        return RepoPermission.builder()
                .id(doc.getString("_id"))
                .username(doc.getString("username"))
                .provider(doc.getString("provider"))
                .path(doc.getString("path"))
                .pathType(RepoPermission.PathType.valueOf(doc.getString("pathType")))
                .operations(RepoPermission.Operations.valueOf(doc.getString("operations")))
                .source(RepoPermission.Source.valueOf(doc.getString("source")))
                .build();
    }
}
