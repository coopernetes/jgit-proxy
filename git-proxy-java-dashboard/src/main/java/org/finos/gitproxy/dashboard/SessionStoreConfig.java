package org.finos.gitproxy.dashboard;

import jakarta.servlet.Filter;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.jetty.config.GitProxyConfig;
import org.finos.gitproxy.jetty.config.ServerConfig.RedisConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the HTTP session store based on {@code server.session-store} in git-proxy.yml.
 *
 * <ul>
 *   <li>{@code none} (default) — in-memory {@link MapSessionRepository}; sessions are lost on restart
 *   <li>{@code jdbc} — {@link JdbcIndexedSessionRepository}; persisted to the configured JDBC database
 *   <li>{@code redis} — {@link RedisIndexedSessionRepository}; persisted to Redis/Valkey
 * </ul>
 *
 * <p>The {@code springSessionRepositoryFilter} bean is always registered so that
 * {@link GitProxyWithDashboardApplication} can unconditionally wire it into the Jetty filter chain ahead of Spring
 * Security.
 */
@Slf4j
@Configuration
public class SessionStoreConfig {

    @Autowired
    private GitProxyConfig gitProxyConfig;

    /** Injected only for JDBC backends — null for MongoDB deployments. */
    @Autowired(required = false)
    private DataSource dataSource;

    @Bean
    @SuppressWarnings("unchecked")
    public SessionRepository<?> sessionRepository() {
        String store = gitProxyConfig.getServer().getSessionStore();
        Duration timeout = Duration.ofSeconds(gitProxyConfig.getAuth().getSessionTimeoutSeconds());
        return switch (store) {
            case "jdbc" -> buildJdbc(timeout);
            case "redis" -> buildRedis(timeout);
            default -> {
                log.info("Session store: in-memory (server.session-store=none). Sessions will not survive restarts.");
                var repo = new MapSessionRepository(new ConcurrentHashMap<>());
                repo.setDefaultMaxInactiveInterval(timeout);
                yield repo;
            }
        };
    }

    @Bean(name = "springSessionRepositoryFilter")
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Filter springSessionRepositoryFilter(SessionRepository sessionRepository) {
        return new SessionRepositoryFilter<>(sessionRepository);
    }

    // ── JDBC ─────────────────────────────────────────────────────────────────

    private JdbcIndexedSessionRepository buildJdbc(Duration timeout) {
        if (dataSource == null) {
            throw new IllegalStateException(
                    "server.session-store=jdbc requires a JDBC database (h2-file, h2-mem, or postgres)."
                            + " Current database.type is mongo — use session-store: none or provision a JDBC database.");
        }
        log.info("Session store: JDBC (server.session-store=jdbc)");
        var jdbcOps = new JdbcTemplate(dataSource);
        var txOps = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var repo = new JdbcIndexedSessionRepository(jdbcOps, txOps);
        repo.setDefaultMaxInactiveInterval(timeout);
        return repo;
    }

    // ── Redis ─────────────────────────────────────────────────────────────────

    private RedisIndexedSessionRepository buildRedis(Duration timeout) {
        RedisConfig redisCfg = gitProxyConfig.getServer().getRedis();
        log.info(
                "Session store: Redis (server.session-store=redis, host={}:{})",
                redisCfg.getHost(),
                redisCfg.getPort());

        var standaloneConfig = new RedisStandaloneConfiguration(redisCfg.getHost(), redisCfg.getPort());
        if (!redisCfg.getPassword().isBlank()) {
            standaloneConfig.setPassword(redisCfg.getPassword());
        }

        LettuceClientConfiguration clientConfig = redisCfg.isSsl()
                ? LettuceClientConfiguration.builder().useSsl().build()
                : LettuceClientConfiguration.defaultConfiguration();

        var factory = new LettuceConnectionFactory(standaloneConfig, clientConfig);
        factory.afterPropertiesSet();

        var template = new RedisTemplate<String, Object>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(RedisSerializer.string());
        template.setHashKeySerializer(RedisSerializer.string());
        template.afterPropertiesSet();

        var repo = new RedisIndexedSessionRepository(template);
        repo.setDefaultMaxInactiveInterval(timeout);
        return repo;
    }
}
