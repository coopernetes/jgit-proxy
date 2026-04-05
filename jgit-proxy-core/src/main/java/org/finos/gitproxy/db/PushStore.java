package org.finos.gitproxy.db;

import java.util.List;
import java.util.Optional;
import org.finos.gitproxy.db.model.Attestation;
import org.finos.gitproxy.db.model.PushQuery;
import org.finos.gitproxy.db.model.PushRecord;
import org.finos.gitproxy.db.model.PushStatus;

/**
 * Storage abstraction for push records. Implementations exist for in-memory, JDBC (H2, SQLite, Postgres), and MongoDB.
 *
 * <p>This is the Java equivalent of git-proxy's Sink interface.
 */
public interface PushStore {

    /**
     * Persist a push record (insert or update). When a push is first received, call this to create the record. Call
     * again after each validation step or status change to update it.
     */
    void save(PushRecord record);

    /** Find a push by its ID. */
    Optional<PushRecord> findById(String id);

    /** Query pushes with optional filters. */
    List<PushRecord> find(PushQuery query);

    /** Delete a push record and all associated data. */
    void delete(String id);

    /**
     * Approve a push. Sets status to APPROVED and records the attestation.
     *
     * @return the updated record
     */
    PushRecord approve(String id, Attestation attestation);

    /**
     * Reject a push. Sets status to REJECTED and records the attestation.
     *
     * @return the updated record
     */
    PushRecord reject(String id, Attestation attestation);

    /**
     * Cancel a push. Sets status to CANCELED and records the attestation.
     *
     * @return the updated record
     */
    PushRecord cancel(String id, Attestation attestation);

    /**
     * Update the forward status of a transparent-proxy re-push after the upstream HTTP response is received. Only
     * touches {@code status} and (on error) {@code errorMessage} — does not modify attestation or steps.
     *
     * @param id the push record ID
     * @param status {@link org.finos.gitproxy.db.model.PushStatus#FORWARDED} on success,
     *     {@link org.finos.gitproxy.db.model.PushStatus#ERROR} on failure
     * @param errorMessage human-readable upstream error detail; {@code null} for FORWARDED
     */
    void updateForwardStatus(String id, PushStatus status, String errorMessage);

    /** Initialize the store (create tables, indexes, etc.). Called once at startup. */
    void initialize();

    /** Close resources. Called on shutdown. */
    default void close() {}
}
