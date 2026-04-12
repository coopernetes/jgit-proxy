package org.finos.gitproxy.db.jdbc;

import lombok.Data;

/**
 * HikariCP connection pool settings, bound from the {@code database.pool:} config block.
 *
 * <p>The default pool size is intentionally small. git-proxy-java push workloads are sequential per user — one push
 * produces one short-lived transaction — so a large pool buys nothing and drives up aggregate connection counts when
 * multiple instances share a database. See the <a
 * href="https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing">HikariCP pool sizing guide</a>.
 */
@Data
public class PoolConfig {

    /**
     * Maximum number of connections in the pool. Default: 3.
     *
     * <p>For sequential push-audit workloads, 3 is sufficient for a single instance and keeps aggregate connection
     * counts manageable when multiple instances share a database. Raise only if profiling shows connection-wait
     * pressure.
     */
    private int maximumPoolSize = 3;

    /**
     * Minimum number of idle connections maintained. Default: -1 (HikariCP default: matches {@code maximumPoolSize}).
     * Set to a lower value (e.g. 1) to allow idle connections to be retired, reducing load on shared databases between
     * push bursts.
     */
    private int minimumIdle = -1;

    /** Maximum milliseconds to wait for a connection before throwing. Default: 30000 (30 s). */
    private long connectionTimeout = 30_000;

    /** Maximum milliseconds a connection may sit idle before being retired. Default: 600000 (10 min). */
    private long idleTimeout = 600_000;

    /** Maximum lifetime of a connection in the pool. Default: 1800000 (30 min). */
    private long maxLifetime = 1_800_000;
}
