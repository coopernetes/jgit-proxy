package org.finos.gitproxy.db.memory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.model.Attestation;
import org.finos.gitproxy.db.model.PushQuery;
import org.finos.gitproxy.db.model.PushRecord;
import org.finos.gitproxy.db.model.PushStatus;

/** Thread-safe in-memory implementation of {@link PushStore}. Useful for development and testing. */
public class InMemoryPushStore implements PushStore {

    private final Map<String, PushRecord> records = new ConcurrentHashMap<>();

    @Override
    public void save(PushRecord record) {
        records.put(record.getId(), record);
    }

    @Override
    public Optional<PushRecord> findById(String id) {
        return Optional.ofNullable(records.get(id));
    }

    @Override
    public List<PushRecord> find(PushQuery query) {
        Stream<PushRecord> stream = records.values().stream();

        if (query.getStatus() != null) {
            stream = stream.filter(r -> r.getStatus() == query.getStatus());
        }
        if (query.getProject() != null) {
            stream = stream.filter(r -> query.getProject().equals(r.getProject()));
        }
        if (query.getRepoName() != null) {
            stream = stream.filter(r -> query.getRepoName().equals(r.getRepoName()));
        }
        if (query.getBranch() != null) {
            stream = stream.filter(r -> query.getBranch().equals(r.getBranch()));
        }
        if (query.getUser() != null) {
            stream = stream.filter(r -> query.getUser().equals(r.getUser()));
        }
        if (query.getAuthorEmail() != null) {
            stream = stream.filter(r -> query.getAuthorEmail().equals(r.getAuthorEmail()));
        }
        if (query.getCommitTo() != null) {
            stream = stream.filter(r -> query.getCommitTo().equals(r.getCommitTo()));
        }

        Comparator<PushRecord> comparator = Comparator.comparing(PushRecord::getTimestamp);
        if (query.isNewestFirst()) {
            comparator = comparator.reversed();
        }

        return stream.sorted(comparator)
                .skip(query.getOffset())
                .limit(query.getLimit())
                .toList();
    }

    @Override
    public void delete(String id) {
        records.remove(id);
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
        PushRecord record = records.get(id);
        if (record == null) return;
        record.setStatus(status);
        record.setForwardedAt(java.time.Instant.now());
        if (errorMessage != null) record.setErrorMessage(errorMessage);
    }

    @Override
    public void initialize() {
        // Nothing to do for in-memory store
    }

    private PushRecord updateStatus(String id, PushStatus status, Attestation attestation) {
        PushRecord record = records.get(id);
        if (record == null) {
            throw new IllegalArgumentException("Push not found: " + id);
        }
        record.setStatus(status);
        record.setAttestation(attestation);
        return record;
    }
}
