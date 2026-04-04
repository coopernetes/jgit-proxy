# Database Layer Refactor Plan

## Goals

- Remove raw JDBC boilerplate (`PreparedStatement`, `ResultSet`, manual `Connection` try/finally)
- Remove manual MongoDB `Document` construction/reads
- Add lightweight query harness without pulling in a full ORM or Spring Boot auto-config
- Keep the existing `PushStore` interface contract unchanged — this is an internal implementation refactor only

---

## Current State

| Class               | Storage                  | Problem                                                                                                 |
| ------------------- | ------------------------ | ------------------------------------------------------------------------------------------------------- |
| `JdbcPushStore`     | H2 / SQLite / PostgreSQL | Raw JDBC: manual statement prep, positional `?` params, no transaction management, exception swallowing |
| `MongoPushStore`    | MongoDB                  | Manual `Document` key/value construction and extraction                                                 |
| `PushRecordMapper`  | —                        | Already close to a `RowMapper<T>`, just not implementing the interface                                  |
| `DataSourceFactory` | HikariCP                 | Already good — wraps `javax.sql.DataSource`, no changes needed                                          |

---

## Recommendation 1 — Add `spring-jdbc` + `spring-tx` to `jgit-proxy-core`

These are standalone Spring modules with no Spring Boot, no Spring Data JPA, no auto-configuration. They give you:

- `NamedParameterJdbcTemplate` — named `:param` SQL with `MapSqlParameterSource`
- `JdbcTemplate` — positional `?` fallback when needed
- `TransactionTemplate` — programmatic transactions, no AOP or proxy magic
- `DataSourceTransactionManager` — wires `DataSource` to the transaction abstraction
- `RowMapper<T>` — interface for result set → object mapping

Add to `jgit-proxy-core/build.gradle`:

```gradle
api 'org.springframework:spring-jdbc:6.2.6'
api 'org.springframework:spring-tx:6.2.6'
```

Version-aligned with the existing `spring-webmvc:6.2.6` used in the dashboard — no version drift.

---

## Recommendation 2 — Refactor `JdbcPushStore` to `NamedParameterJdbcTemplate`

Replace manual `PreparedStatement` / `ResultSet` / `Connection` lifecycle with template-based calls.

Named parameters (`:status`, `:pushId`) are preferable to positional `?` for column-heavy queries. The existing `find(PushQuery)` dynamic WHERE clause is a direct fit for `MapSqlParameterSource`.

Key construction change:

```java
private final NamedParameterJdbcTemplate jdbc;
private final TransactionTemplate tx;

public JdbcPushStore(DataSource ds) {
    this.jdbc = new NamedParameterJdbcTemplate(ds);
    this.tx   = new TransactionTemplate(new DataSourceTransactionManager(ds));
}
```

`approve()`, `reject()`, and `cancel()` are the only methods that touch multiple tables (status update + attestation insert) and need `tx.execute(...)`. All read paths (`find`, `findById`) are plain queries with no transaction wrapper needed.

---

## Recommendation 3 — Promote `PushRecordMapper` to `RowMapper<PushRecord>`

The existing mapper class already does the right work. Implement the Spring interface so it composes cleanly with the template:

```java
public class PushRecordMapper implements RowMapper<PushRecord> {
    @Override
    public PushRecord mapRow(ResultSet rs, int rowNum) throws SQLException { ... }
}
```

Create equivalents for the child entities:

- `PushStepMapper implements RowMapper<PushStep>`
- `PushCommitMapper implements RowMapper<PushCommit>`
- `AttestationMapper implements RowMapper<Attestation>`

Each mapper class is trivially unit-testable with a mocked `ResultSet`.

---

## Recommendation 4 — MongoDB: POJO Codec Registry (no `spring-data-mongodb`)

`spring-data-mongodb` gives `MongoTemplate` but drags in `spring-data-commons` (~800KB) and its annotation-driven mapping layer. The MongoDB Java driver already ships a **POJO codec registry** that handles marshalling with zero additional dependencies.

Register domain classes at startup:

```java
CodecRegistry pojoRegistry = fromRegistries(
    MongoClientSettings.getDefaultCodecRegistry(),
    fromProviders(PojoCodecProvider.builder()
        .register(PushRecord.class, PushCommit.class, PushStep.class, Attestation.class)
        .automatic(true)
        .build())
);
```

Then `MongoPushStore` works with typed collections instead of raw `Document`:

```java
MongoCollection<PushRecord> collection = mongoClient
    .getDatabase("gitproxy")
    .withCodecRegistry(pojoRegistry)
    .getCollection("pushes", PushRecord.class);

// clean reads/writes
collection.find(eq("status", PushStatus.APPROVED)).into(new ArrayList<>());
collection.findOneAndUpdate(eq("id", id),
    Updates.combine(Updates.set("status", APPROVED), ...),
    new FindOneAndUpdateOptions().returnDocument(AFTER));
```

`Filters`, `Updates`, and `Sorts` from `com.mongodb.client.model` are the Mongo-native equivalent of `MapSqlParameterSource` — composable and readable without string `Document` keys everywhere.

Requires the domain model classes (`PushRecord`, `PushStep`, etc.) to have default constructors and standard getters/setters (or public fields). If they currently use builder/record patterns, a small codec customisation or `@BsonProperty` annotations bridge the gap.

---

## Recommendation 5 — Dashboard Wiring (no Boot, no auto-config)

The dashboard already registers beans manually in `SpringWebConfig`. Expose the JDBC helpers as Spring-managed beans so controllers and any future services receive them by injection rather than constructing templates themselves:

```java
// in SpringWebConfig (or a new DataConfig @Configuration)
@Bean
public NamedParameterJdbcTemplate jdbcTemplate(DataSource dataSource) {
    return new NamedParameterJdbcTemplate(dataSource);
}

@Bean
public DataSourceTransactionManager transactionManager(DataSource dataSource) {
    return new DataSourceTransactionManager(dataSource);
}
```

`DataSource` is already constructed by `DataSourceFactory` and passed in externally — this just promotes it to a Spring bean so the wiring stays explicit and testable without any `@Autowired` magic on construction.

---

## What This Does Not Change

- `PushStore` interface — unchanged, no API break
- `DataSourceFactory` / HikariCP setup — unchanged
- `schema.sql` — raw SQL stays as the schema of record, no JPA `@Entity` annotations, no Flyway (unless added separately)
- `PushStoreFactory` convenience builders — unchanged
- The MongoDB document structure — POJO codec maps to the same document shape as the current `Document`-based code

---

## Summary

| Concern                    | Approach                                                    |
| -------------------------- | ----------------------------------------------------------- |
| SQL connection boilerplate | `NamedParameterJdbcTemplate`                                |
| Transaction management     | `TransactionTemplate` + `DataSourceTransactionManager`      |
| Row mapping                | `RowMapper<T>` per entity                                   |
| MongoDB document mapping   | POJO codec registry (driver-native)                         |
| Spring Boot / JPA          | Not needed — `spring-jdbc` + `spring-tx` are self-contained |
| ORM                        | None — schema.sql is the source of truth                    |

New transitive deps (dashboard): `spring-jdbc` + `spring-tx` only. Both are already version-aligned with Spring MVC 6.2.6 in use today.
