package org.finos.gitproxy.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe in-memory {@link RepoPermissionStore}. Used for the {@code memory} database type. */
public class InMemoryRepoPermissionStore implements RepoPermissionStore {

    private final Map<String, RepoPermission> store = new ConcurrentHashMap<>();

    @Override
    public void save(RepoPermission permission) {
        store.put(permission.getId(), permission);
    }

    @Override
    public void delete(String id) {
        store.remove(id);
    }

    @Override
    public Optional<RepoPermission> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<RepoPermission> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public List<RepoPermission> findByUsername(String username) {
        return store.values().stream()
                .filter(p -> username.equals(p.getUsername()))
                .toList();
    }

    @Override
    public List<RepoPermission> findByProvider(String provider) {
        return store.values().stream()
                .filter(p -> provider.equals(p.getProvider()))
                .toList();
    }
}
