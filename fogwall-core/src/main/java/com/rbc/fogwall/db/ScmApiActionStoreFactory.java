package com.rbc.fogwall.db;

import com.rbc.fogwall.db.jdbc.JdbcScmApiActionStore;
import javax.sql.DataSource;

/** Factory for creating {@link ScmApiActionStore} instances, mirroring {@link PushStoreFactory}'s shape. */
public final class ScmApiActionStoreFactory {

    private ScmApiActionStoreFactory() {}

    /** Create a store from an already-configured {@link DataSource} (shared pool use case). */
    public static ScmApiActionStore fromDataSource(DataSource dataSource) {
        JdbcScmApiActionStore store = new JdbcScmApiActionStore(dataSource);
        store.initialize();
        return store;
    }
}
