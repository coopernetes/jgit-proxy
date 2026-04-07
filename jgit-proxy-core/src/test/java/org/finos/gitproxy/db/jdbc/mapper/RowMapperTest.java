package org.finos.gitproxy.db.jdbc.mapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import org.finos.gitproxy.db.model.AccessRule;
import org.finos.gitproxy.db.model.FetchRecord;
import org.junit.jupiter.api.Test;

class RowMapperTest {

    // ---- AccessRuleRowMapper ----

    @Test
    void accessRule_allFields_mapped() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn("rule-1");
        when(rs.getString("provider")).thenReturn("github");
        when(rs.getString("slug")).thenReturn("/org/*");
        when(rs.getString("owner")).thenReturn("org");
        when(rs.getString("name")).thenReturn("*");
        when(rs.getString("access")).thenReturn("DENY");
        when(rs.getString("operations")).thenReturn("PUSH");
        when(rs.getString("description")).thenReturn("Block all pushes");
        when(rs.getBoolean("enabled")).thenReturn(true);
        when(rs.getInt("rule_order")).thenReturn(50);
        when(rs.getString("source")).thenReturn("CONFIG");

        AccessRule rule = AccessRuleRowMapper.INSTANCE.mapRow(rs, 0);

        assertEquals("rule-1", rule.getId());
        assertEquals("github", rule.getProvider());
        assertEquals("/org/*", rule.getSlug());
        assertEquals("org", rule.getOwner());
        assertEquals("*", rule.getName());
        assertEquals(AccessRule.Access.DENY, rule.getAccess());
        assertEquals(AccessRule.Operations.PUSH, rule.getOperations());
        assertEquals("Block all pushes", rule.getDescription());
        assertTrue(rule.isEnabled());
        assertEquals(50, rule.getRuleOrder());
        assertEquals(AccessRule.Source.CONFIG, rule.getSource());
    }

    @Test
    void accessRule_nullableFields_null() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn("rule-2");
        when(rs.getString("provider")).thenReturn(null);
        when(rs.getString("slug")).thenReturn(null);
        when(rs.getString("owner")).thenReturn(null);
        when(rs.getString("name")).thenReturn(null);
        when(rs.getString("access")).thenReturn("ALLOW");
        when(rs.getString("operations")).thenReturn("ALL");
        when(rs.getString("description")).thenReturn(null);
        when(rs.getBoolean("enabled")).thenReturn(false);
        when(rs.getInt("rule_order")).thenReturn(100);
        when(rs.getString("source")).thenReturn("DB");

        AccessRule rule = AccessRuleRowMapper.INSTANCE.mapRow(rs, 1);

        assertNull(rule.getProvider());
        assertNull(rule.getSlug());
        assertNull(rule.getOwner());
        assertNull(rule.getName());
        assertNull(rule.getDescription());
        assertEquals(AccessRule.Access.ALLOW, rule.getAccess());
        assertEquals(AccessRule.Operations.ALL, rule.getOperations());
        assertEquals(AccessRule.Source.DB, rule.getSource());
        assertFalse(rule.isEnabled());
    }

    // ---- FetchRecordRowMapper ----

    @Test
    void fetchRecord_allFields_mapped() throws SQLException {
        Instant now = Instant.now();
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn("fetch-1");
        when(rs.getTimestamp("timestamp")).thenReturn(Timestamp.from(now));
        when(rs.getString("provider")).thenReturn("github");
        when(rs.getString("owner")).thenReturn("myorg");
        when(rs.getString("repo_name")).thenReturn("myrepo");
        when(rs.getString("result")).thenReturn("ALLOWED");
        when(rs.getString("push_username")).thenReturn("me");
        when(rs.getString("resolved_user")).thenReturn("alice");

        FetchRecord record = FetchRecordRowMapper.INSTANCE.mapRow(rs, 0);

        assertEquals("fetch-1", record.getId());
        assertEquals(now, record.getTimestamp());
        assertEquals("github", record.getProvider());
        assertEquals("myorg", record.getOwner());
        assertEquals("myrepo", record.getRepoName());
        assertEquals(FetchRecord.Result.ALLOWED, record.getResult());
        assertEquals("me", record.getPushUsername());
        assertEquals("alice", record.getResolvedUser());
    }

    @Test
    void fetchRecord_blockedResult_mapped() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn("fetch-2");
        when(rs.getTimestamp("timestamp")).thenReturn(Timestamp.from(Instant.now()));
        when(rs.getString("provider")).thenReturn("gitlab");
        when(rs.getString("owner")).thenReturn(null);
        when(rs.getString("repo_name")).thenReturn(null);
        when(rs.getString("result")).thenReturn("BLOCKED");
        when(rs.getString("push_username")).thenReturn(null);
        when(rs.getString("resolved_user")).thenReturn(null);

        FetchRecord record = FetchRecordRowMapper.INSTANCE.mapRow(rs, 1);

        assertEquals(FetchRecord.Result.BLOCKED, record.getResult());
        assertNull(record.getOwner());
        assertNull(record.getResolvedUser());
    }

    @Test
    void fetchRecord_nullTimestamp_mapsToNull() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn("fetch-3");
        when(rs.getTimestamp("timestamp")).thenReturn(null);
        when(rs.getString("provider")).thenReturn("github");
        when(rs.getString("owner")).thenReturn("org");
        when(rs.getString("repo_name")).thenReturn("repo");
        when(rs.getString("result")).thenReturn("ALLOWED");
        when(rs.getString("push_username")).thenReturn("me");
        when(rs.getString("resolved_user")).thenReturn(null);

        FetchRecord record = FetchRecordRowMapper.INSTANCE.mapRow(rs, 2);

        assertNull(record.getTimestamp());
    }
}
