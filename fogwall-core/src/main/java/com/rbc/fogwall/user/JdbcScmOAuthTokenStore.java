package com.rbc.fogwall.user;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** JDBC-backed {@link ScmOAuthTokenStore}, keeping tokens in {@code user_scm_tokens}. */
@Slf4j
public class JdbcScmOAuthTokenStore implements ScmOAuthTokenStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcScmOAuthTokenStore(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    /** Upserts the token for {@code (username, provider)}, replacing any prior token for the same pair. */
    @Override
    public void save(
            String username,
            String provider,
            byte[] encryptedAccessToken,
            byte[] encryptedRefreshToken,
            String scopes,
            Instant expiresAt) {
        var params = new HashMap<String, Object>();
        params.put("u", username);
        params.put("provider", provider);
        params.put("accessToken", encryptedAccessToken);
        params.put("refreshToken", encryptedRefreshToken);
        params.put("scopes", scopes);
        params.put("expiresAt", expiresAt != null ? Timestamp.from(expiresAt) : null);
        params.put("authorizedAt", Timestamp.from(Instant.now()));

        int updated = jdbc.update(
                "UPDATE user_scm_tokens SET access_token = :accessToken, refresh_token = :refreshToken, "
                        + "scopes = :scopes, expires_at = :expiresAt, authorized_at = :authorizedAt "
                        + "WHERE username = :u AND provider = :provider",
                params);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO user_scm_tokens "
                            + "(username, provider, access_token, refresh_token, scopes, expires_at, authorized_at) "
                            + "VALUES (:u, :provider, :accessToken, :refreshToken, :scopes, :expiresAt, :authorizedAt)",
                    params);
        }
        log.debug("Stored OAuth token for user '{}' / provider '{}'", username, provider);
    }

    /**
     * Returns the stored encrypted access token for {@code (username, provider)}, if any — used to revoke it upstream
     * (#40) before removing the local record. Caller decrypts via {@code TokenCipher}.
     */
    @Override
    public Optional<byte[]> findAccessToken(String username, String provider) {
        return jdbc
                .query(
                        "SELECT access_token FROM user_scm_tokens WHERE username = :u AND provider = :provider",
                        Map.of("u", username, "provider", provider),
                        (rs, rowNum) -> rs.getBytes("access_token"))
                .stream()
                .findFirst();
    }

    /** Removes the stored token for {@code (username, provider)}, if any. No-ops if none exists. */
    @Override
    public void remove(String username, String provider) {
        jdbc.update(
                "DELETE FROM user_scm_tokens WHERE username = :u AND provider = :provider",
                Map.of("u", username, "provider", provider));
        log.debug("Removed OAuth token for user '{}' / provider '{}'", username, provider);
    }
}
