package com.rbc.fogwall.db;

import com.rbc.fogwall.db.model.ScmApiActionQuery;
import com.rbc.fogwall.db.model.ScmApiActionRecord;
import java.util.List;
import java.util.Optional;

/**
 * Storage abstraction for SCM API proxy audit records. Implementations exist for JDBC and MongoDB, mirroring
 * {@link PushStore}'s dual-backend pattern.
 *
 * <p>Unlike {@link PushStore}, records here are write-once: a mutation's allowlist/permission decision and forward
 * outcome are all known synchronously in one pass, so there is no pending-review lifecycle to update.
 */
public interface ScmApiActionStore {

    /** Persist a new action record. */
    void save(ScmApiActionRecord record);

    /** Find an action record by its ID. */
    Optional<ScmApiActionRecord> findById(String id);

    /** Find action records matching the given query, most recent first by default. */
    List<ScmApiActionRecord> find(ScmApiActionQuery query);

    /** Initialize the store (create tables/indexes). Called once at startup. */
    void initialize();

    /** Close resources. Called on shutdown. */
    default void close() {}
}
