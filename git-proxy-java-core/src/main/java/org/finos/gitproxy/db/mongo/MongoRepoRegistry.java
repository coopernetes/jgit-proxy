package org.finos.gitproxy.db.mongo;

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
import org.finos.gitproxy.db.RepoRegistry;
import org.finos.gitproxy.db.model.AccessRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MongoDB implementation of {@link RepoRegistry}. */
public class MongoRepoRegistry implements RepoRegistry {

    private static final Logger log = LoggerFactory.getLogger(MongoRepoRegistry.class);
    private static final String COLLECTION_NAME = "access_rules";

    private final MongoDatabase database;

    public MongoRepoRegistry(MongoClient mongoClient, String databaseName) {
        this.database = mongoClient.getDatabase(databaseName);
    }

    @Override
    public void initialize() {
        MongoCollection<Document> col = getCollection();
        col.createIndex(Indexes.ascending("ruleOrder", "_id"));
        col.createIndex(Indexes.ascending("enabled", "provider"));
        log.info("MongoDB repo registry initialized");
    }

    @Override
    public void save(AccessRule rule) {
        getCollection().insertOne(toDocument(rule));
    }

    @Override
    public void update(AccessRule rule) {
        getCollection().replaceOne(Filters.eq("_id", rule.getId()), toDocument(rule));
    }

    @Override
    public void delete(String id) {
        getCollection().deleteOne(Filters.eq("_id", id));
    }

    @Override
    public Optional<AccessRule> findById(String id) {
        Document doc = getCollection().find(Filters.eq("_id", id)).first();
        return Optional.ofNullable(doc).map(MongoRepoRegistry::fromDocument);
    }

    @Override
    public List<AccessRule> findAll() {
        List<AccessRule> results = new ArrayList<>();
        getCollection().find().sort(Sorts.ascending("ruleOrder", "_id")).forEach(doc -> results.add(fromDocument(doc)));
        return results;
    }

    @Override
    public List<AccessRule> findEnabledForProvider(String provider) {
        List<AccessRule> results = new ArrayList<>();
        getCollection()
                .find(Filters.and(
                        Filters.eq("enabled", true),
                        Filters.or(
                                Filters.exists("provider", false),
                                Filters.eq("provider", null),
                                Filters.eq("provider", provider))))
                .sort(Sorts.ascending("ruleOrder", "_id"))
                .forEach(doc -> results.add(fromDocument(doc)));
        return results;
    }

    private MongoCollection<Document> getCollection() {
        return database.getCollection(COLLECTION_NAME);
    }

    private static Document toDocument(AccessRule r) {
        return new Document("_id", r.getId())
                .append("provider", r.getProvider())
                .append("slug", r.getSlug())
                .append("owner", r.getOwner())
                .append("name", r.getName())
                .append("access", r.getAccess().name())
                .append("operations", r.getOperations().name())
                .append("description", r.getDescription())
                .append("enabled", r.isEnabled())
                .append("ruleOrder", r.getRuleOrder())
                .append("source", r.getSource().name());
    }

    private static AccessRule fromDocument(Document doc) {
        return AccessRule.builder()
                .id(doc.getString("_id"))
                .provider(doc.getString("provider"))
                .slug(doc.getString("slug"))
                .owner(doc.getString("owner"))
                .name(doc.getString("name"))
                .access(AccessRule.Access.valueOf(doc.getString("access")))
                .operations(AccessRule.Operations.valueOf(doc.getString("operations")))
                .description(doc.getString("description"))
                .enabled(doc.getBoolean("enabled", true))
                .ruleOrder(doc.getInteger("ruleOrder", 100))
                .source(AccessRule.Source.valueOf(doc.getString("source")))
                .build();
    }
}
