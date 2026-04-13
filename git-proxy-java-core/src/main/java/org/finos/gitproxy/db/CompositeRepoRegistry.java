package org.finos.gitproxy.db;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.finos.gitproxy.db.model.AccessRule;

/**
 * A {@link RepoRegistry} that merges read-only config-seeded rules (in-memory) with operator-managed rules (DB-backed).
 * CONFIG rules are never written to the database, so there are no stale duplicates across restarts.
 *
 * <ul>
 *   <li>Reads ({@link #findAll}, {@link #findEnabledForProvider}) return merged results sorted by {@code rule_order}.
 *   <li>Writes ({@link #save}, {@link #update}, {@link #delete}) delegate only to the DB registry.
 * </ul>
 */
public class CompositeRepoRegistry implements RepoRegistry {

    private final RepoRegistry configRegistry;
    private final RepoRegistry dbRegistry;

    public CompositeRepoRegistry(RepoRegistry configRegistry, RepoRegistry dbRegistry) {
        this.configRegistry = configRegistry;
        this.dbRegistry = dbRegistry;
    }

    @Override
    public void initialize() {
        dbRegistry.initialize();
    }

    @Override
    public void save(AccessRule rule) {
        dbRegistry.save(rule);
    }

    @Override
    public void update(AccessRule rule) {
        dbRegistry.update(rule);
    }

    @Override
    public void delete(String id) {
        dbRegistry.delete(id);
    }

    @Override
    public Optional<AccessRule> findById(String id) {
        Optional<AccessRule> fromDb = dbRegistry.findById(id);
        if (fromDb.isPresent()) return fromDb;
        return configRegistry.findById(id);
    }

    @Override
    public List<AccessRule> findAll() {
        return merged(configRegistry.findAll(), dbRegistry.findAll());
    }

    @Override
    public List<AccessRule> findEnabledForProvider(String provider) {
        return merged(configRegistry.findEnabledForProvider(provider), dbRegistry.findEnabledForProvider(provider));
    }

    /**
     * Re-seeds the in-memory config registry with a new set of CONFIG rules. DB-sourced rules are not affected. This
     * override is necessary because the default {@link RepoRegistry#seedFromConfig} would incorrectly attempt to delete
     * config rules via the DB registry.
     */
    @Override
    public void seedFromConfig(List<AccessRule> rules) {
        configRegistry.seedFromConfig(rules);
    }

    private static List<AccessRule> merged(List<AccessRule> config, List<AccessRule> db) {
        List<AccessRule> combined = new ArrayList<>(config.size() + db.size());
        combined.addAll(config);
        combined.addAll(db);
        combined.sort(Comparator.comparingInt(AccessRule::getRuleOrder).thenComparing(AccessRule::getId));
        return combined;
    }
}
