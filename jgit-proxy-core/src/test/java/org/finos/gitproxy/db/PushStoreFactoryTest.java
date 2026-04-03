package org.finos.gitproxy.db;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PushStoreFactoryTest {

    @Test
    void inMemory_returnsInitializedStore() {
        PushStore store = PushStoreFactory.inMemory();
        assertNotNull(store);
    }

    @Test
    void h2InMemory_defaultName_returnsInitializedStore() {
        PushStore store = PushStoreFactory.h2InMemory();
        assertNotNull(store);
    }

    @Test
    void h2InMemory_customName_returnsInitializedStore() {
        PushStore store = PushStoreFactory.h2InMemory("factory_test_" + System.nanoTime());
        assertNotNull(store);
    }

    @Test
    void h2File_returnsInitializedStore(@TempDir java.nio.file.Path dir) {
        String path = dir.resolve("test").toAbsolutePath().toString();
        PushStore store = PushStoreFactory.h2File(path);
        assertNotNull(store);
    }

    @Test
    void sqlite_returnsInitializedStore(@TempDir java.nio.file.Path dir) {
        String path = dir.resolve("test.db").toAbsolutePath().toString();
        PushStore store = PushStoreFactory.sqlite(path);
        assertNotNull(store);
    }

    @Test
    void fromJdbcUrl_h2_returnsInitializedStore() {
        String url = "jdbc:h2:mem:factory_jdbc_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        PushStore store = PushStoreFactory.fromJdbcUrl(url, null, null);
        assertNotNull(store);
    }
}
