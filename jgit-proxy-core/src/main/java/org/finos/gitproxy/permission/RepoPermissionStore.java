package org.finos.gitproxy.permission;

import java.util.List;
import java.util.Optional;

/** Persistence interface for {@link RepoPermission} grants. */
public interface RepoPermissionStore {

    /** Called once at startup; implementations may use this to create schema or seed data. */
    default void initialize() {}

    void save(RepoPermission permission);

    void delete(String id);

    Optional<RepoPermission> findById(String id);

    List<RepoPermission> findAll();

    List<RepoPermission> findByUsername(String username);

    List<RepoPermission> findByProvider(String provider);
}
