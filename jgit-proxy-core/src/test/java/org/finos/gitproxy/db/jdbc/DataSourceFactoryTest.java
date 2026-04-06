package org.finos.gitproxy.db.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataSourceFactoryTest {

    @Test
    void h2InMemory_returnsConnectableDataSource() throws SQLException {
        DataSource ds = DataSourceFactory.h2InMemory("test_h2mem_" + System.nanoTime());
        try (Connection conn = ds.getConnection()) {
            assertFalse(conn.isClosed());
        }
    }

    @Test
    void h2File_returnsConnectableDataSource(@TempDir java.nio.file.Path dir) throws SQLException {
        String path = dir.resolve("test").toAbsolutePath().toString();
        DataSource ds = DataSourceFactory.h2File(path);
        try (Connection conn = ds.getConnection()) {
            assertFalse(conn.isClosed());
        }
    }

    @Test
    void fromUrl_h2InMemory_returnsConnectableDataSource() throws SQLException {
        String url = "jdbc:h2:mem:fromurl_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DataSource ds = DataSourceFactory.fromUrl(url, null, null);
        try (Connection conn = ds.getConnection()) {
            assertFalse(conn.isClosed());
        }
    }
}
