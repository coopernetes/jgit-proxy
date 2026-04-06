package org.finos.gitproxy.jetty.config;

import lombok.Data;

/** Binds the {@code database:} block in git-proxy.yml. */
@Data
public class DatabaseConfig {

    /**
     * Storage backend. Values: {@code memory}, {@code h2-mem} (default), {@code h2-file}, {@code postgres},
     * {@code mongo}.
     */
    private String type = "h2-mem";

    /** Database name. Used by h2-mem, h2-file, postgres, mongo. */
    private String name = "gitproxy";

    /** File path. Used by h2-file (no extension). */
    private String path = "";

    // --- postgres ---
    private String host = "localhost";
    private int port = 5432;
    private String username = "";
    private String password = "";

    // --- mongo ---
    private String url = "mongodb://gitproxy:gitproxy@localhost:27017";
}
