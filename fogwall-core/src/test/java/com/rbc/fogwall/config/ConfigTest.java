package com.rbc.fogwall.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ConfigTest {

    // --- CommitConfig ---

    @Test
    void commitConfig_defaultConfig_hasNoRestrictions() {
        CommitConfig config = CommitConfig.defaultConfig();
        assertNotNull(config);
        assertNotNull(config.getAuthor());
        assertNotNull(config.getAuthor().getEmail());
        assertFalse(config.getAuthor().getEmail().isConfigured());
        assertTrue(config.getAuthor().getEmail().getRules().isEmpty());
        assertNull(config.getAuthor().getEmail().violationReason("anyone@anywhere.io"));
        assertNotNull(config.getMessage());
        assertNotNull(config.getMessage().getBlock());
        assertTrue(config.getMessage().getBlock().getLiterals().isEmpty());
        assertTrue(config.getMessage().getBlock().getPatterns().isEmpty());
        assertTrue(config.getTrailers().isEffectivelyOff());
    }

    @Test
    void emailConfig_domainAllowRule_gatesByDomain() {
        CommitConfig.EmailConfig email = CommitConfig.EmailConfig.builder()
                .rules(List.of(EmailRule.allow(EmailRule.Field.DOMAIN, EmailRule.Match.REGEX, "example\\.com$")))
                .build();
        assertNull(email.violationReason("dev@example.com"));
        assertNotNull(email.violationReason("dev@gmail.com"));
    }

    @Test
    void emailConfig_localBlockRule_blocksLocalPart() {
        CommitConfig.EmailConfig email = CommitConfig.EmailConfig.builder()
                .rules(List.of(EmailRule.block(EmailRule.Field.LOCAL, EmailRule.Match.REGEX, "^noreply$")))
                .build();
        assertNotNull(email.violationReason("noreply@example.com"));
        assertNull(email.violationReason("dev@example.com"));
    }

    @Test
    void emailConfig_addressLiteralAllow_permitsExactAddressOnly() {
        CommitConfig.EmailConfig email = CommitConfig.EmailConfig.builder()
                .rules(List.of(
                        EmailRule.allow(EmailRule.Field.DOMAIN, EmailRule.Match.REGEX, "company\\.com$"),
                        EmailRule.allow(EmailRule.Field.ADDRESS, EmailRule.Match.LITERAL, "noreply@anthropic.com")))
                .build();
        assertNull(email.violationReason("dev@company.com"));
        assertNull(email.violationReason("noreply@anthropic.com"));
        assertNotNull(email.violationReason("someone@anthropic.com"));
    }

    @Test
    void emailConfig_blockWinsOverAllowOnSameEmail() {
        CommitConfig.EmailConfig email = CommitConfig.EmailConfig.builder()
                .rules(List.of(
                        EmailRule.allow(EmailRule.Field.DOMAIN, EmailRule.Match.REGEX, "corp\\.com$"),
                        EmailRule.block(EmailRule.Field.LOCAL, EmailRule.Match.REGEX, "^svc-")))
                .build();
        assertNotNull(email.violationReason("svc-ci@corp.com"), "block wins even when the allow rule also matches");
        assertNull(email.violationReason("dev@corp.com"));
    }

    @Test
    void emailConfig_emptyAndMalformedEmails() {
        CommitConfig.EmailConfig email = CommitConfig.EmailConfig.builder()
                .rules(List.of(EmailRule.allow(EmailRule.Field.DOMAIN, EmailRule.Match.REGEX, "corp\\.com$")))
                .build();
        assertEquals("empty email", email.violationReason(""));
        assertEquals("empty email", email.violationReason(null));
        assertEquals("missing @ in email", email.violationReason("notanemail"));
    }

    @Test
    void emailConfig_notConfigured_permitsEverything() {
        CommitConfig.EmailConfig email = CommitConfig.EmailConfig.builder().build();
        assertFalse(email.isConfigured());
        assertNull(email.violationReason("anyone@anywhere.io"));
        assertNull(email.violationReason(""));
    }

    @Test
    void coAuthorPolicy_fromString_parsesAllAndDefaults() {
        assertEquals(CommitConfig.CoAuthorPolicy.BAN, CommitConfig.CoAuthorPolicy.fromString("ban"));
        assertEquals(CommitConfig.CoAuthorPolicy.ALLOWLIST, CommitConfig.CoAuthorPolicy.fromString("ALLOWLIST"));
        assertEquals(CommitConfig.CoAuthorPolicy.REQUIRE, CommitConfig.CoAuthorPolicy.fromString("require"));
        assertEquals(CommitConfig.CoAuthorPolicy.OFF, CommitConfig.CoAuthorPolicy.fromString("off"));
        assertEquals(CommitConfig.CoAuthorPolicy.OFF, CommitConfig.CoAuthorPolicy.fromString(null));
        assertEquals(CommitConfig.CoAuthorPolicy.OFF, CommitConfig.CoAuthorPolicy.fromString("nonsense"));
    }

    @Test
    void trailerPolicy_isEffectivelyOff_reflectsBothControls() {
        assertTrue(CommitConfig.TrailerPolicyConfig.builder().build().isEffectivelyOff());
        assertFalse(CommitConfig.TrailerPolicyConfig.builder()
                .signedOffBy(
                        CommitConfig.SignedOffByConfig.builder().require(true).build())
                .build()
                .isEffectivelyOff());
        assertFalse(CommitConfig.TrailerPolicyConfig.builder()
                .coAuthoredBy(CommitConfig.CoAuthoredByConfig.builder()
                        .policy(CommitConfig.CoAuthorPolicy.REQUIRE)
                        .build())
                .build()
                .isEffectivelyOff());
    }

    @Test
    void commitConfig_builder_setsMessageBlockLiterals() {
        CommitConfig config = CommitConfig.builder()
                .message(CommitConfig.MessageConfig.builder()
                        .block(BlockConfig.builder()
                                .literals(List.of("WIP", "DO NOT MERGE"))
                                .build())
                        .build())
                .build();
        assertEquals(
                List.of("WIP", "DO NOT MERGE"), config.getMessage().getBlock().getLiterals());
    }

    @Test
    void commitConfig_builder_setsMessageBlockPatterns() {
        Pattern p = Pattern.compile("password\\s*=");
        CommitConfig config = CommitConfig.builder()
                .message(CommitConfig.MessageConfig.builder()
                        .block(BlockConfig.builder().patterns(List.of(p)).build())
                        .build())
                .build();
        assertEquals(1, config.getMessage().getBlock().getPatterns().size());
        assertSame(p, config.getMessage().getBlock().getPatterns().get(0));
    }

    // --- CommitConfig.CommitAttributionPolicyMode ---

    @Test
    void commitAttributionPolicyMode_fromString_null_returnsWarn() {
        assertEquals(
                CommitConfig.CommitAttributionPolicyMode.WARN,
                CommitConfig.CommitAttributionPolicyMode.fromString(null));
    }

    @Test
    void commitAttributionPolicyMode_fromString_strict_returnsStrict() {
        assertEquals(
                CommitConfig.CommitAttributionPolicyMode.STRICT,
                CommitConfig.CommitAttributionPolicyMode.fromString("strict"));
        assertEquals(
                CommitConfig.CommitAttributionPolicyMode.STRICT,
                CommitConfig.CommitAttributionPolicyMode.fromString("STRICT"));
    }

    @Test
    void commitAttributionPolicyMode_fromString_off_returnsOff() {
        assertEquals(
                CommitConfig.CommitAttributionPolicyMode.OFF,
                CommitConfig.CommitAttributionPolicyMode.fromString("off"));
    }

    @Test
    void commitAttributionPolicyMode_fromString_unknown_returnsWarn() {
        assertEquals(
                CommitConfig.CommitAttributionPolicyMode.WARN,
                CommitConfig.CommitAttributionPolicyMode.fromString("invalid"));
    }

    // --- GpgConfig ---

    @Test
    void gpgConfig_defaultConfig_isDisabled() {
        GpgConfig config = GpgConfig.defaultConfig();
        assertFalse(config.isEnabled());
        assertFalse(config.isRequireSignedCommits());
        assertNull(config.getTrustedKeysFile());
        assertNull(config.getTrustedKeysInline());
    }

    @Test
    void gpgConfig_builder_setsEnabled() {
        GpgConfig config =
                GpgConfig.builder().enabled(true).requireSignedCommits(true).build();
        assertTrue(config.isEnabled());
        assertTrue(config.isRequireSignedCommits());
    }

    @Test
    void gpgConfig_builder_setsTrustedKeysFile() {
        GpgConfig config =
                GpgConfig.builder().trustedKeysFile("/path/to/keys.asc").build();
        assertEquals("/path/to/keys.asc", config.getTrustedKeysFile());
    }

    @Test
    void gpgConfig_builder_setsTrustedKeysInline() {
        GpgConfig config =
                GpgConfig.builder().trustedKeysInline("-----BEGIN PGP...").build();
        assertEquals("-----BEGIN PGP...", config.getTrustedKeysInline());
    }
}
