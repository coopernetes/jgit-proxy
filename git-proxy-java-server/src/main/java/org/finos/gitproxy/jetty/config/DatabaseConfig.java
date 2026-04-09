package org.finos.gitproxy.jetty.config;

import lombok.Data;

/** Binds the {@code database:} block in git-proxy.yml. */
@Data
public class DatabaseConfig {

    /** Storage backend. Values: {@code h2-mem} (default), {@code h2-file}, {@code postgres}, {@code mongo}. */
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

    /**
     * Full connection string. When non-blank, takes precedence over individual {@code host}/{@code port}/{@code name}
     * fields.
     *
     * <ul>
     *   <li><b>postgres</b> — JDBC URL, e.g. {@code jdbc:postgresql://host:5432/db?sslmode=verify-full}
     *   <li><b>mongo</b> — MongoDB connection URI, e.g. {@code mongodb://user:pass@host:27017/db?tls=true}
     * </ul>
     *
     * For mongo, the database name can be embedded in the URI path; the {@code name} field is used as a fallback when
     * the URI contains no path component.
     */
    private String url = "";
}
