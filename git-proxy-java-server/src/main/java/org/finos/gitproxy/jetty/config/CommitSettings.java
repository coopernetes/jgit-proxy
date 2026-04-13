package org.finos.gitproxy.jetty.config;

import lombok.Data;

/**
 * Binds the {@code commit:} block in git-proxy.yml. This is the raw YAML DTO — all pattern strings are kept as
 * {@code String} fields and compiled to {@link java.util.regex.Pattern} by {@link JettyConfigurationBuilder} when
 * constructing the core {@link org.finos.gitproxy.config.CommitConfig}.
 *
 * <p>Contains only per-commit checks (identity verification, author email, commit message). Push-level checks live in
 * {@link DiffScanSettings} and {@link SecretScanSettings}.
 */
@Data
public class CommitSettings {

    /**
     * Controls whether SCM usernames & author/committer emails are verified against an authenticated user. Options:
     * {@code warn} (default), {@code strict}, {@code off}.
     */
    private String identityVerification = "warn";

    private AuthorSettings author = new AuthorSettings();
    private MessageSettings message = new MessageSettings();

    @Data
    public static class AuthorSettings {
        private EmailSettings email = new EmailSettings();
    }

    @Data
    public static class EmailSettings {
        private DomainSettings domain = new DomainSettings();
        private LocalSettings local = new LocalSettings();
    }

    @Data
    public static class DomainSettings {
        /** Regex the email domain must match. Empty = allow all. */
        private String allow = "";
    }

    @Data
    public static class LocalSettings {
        /** Regex blocking specific local-parts (the part before @). Empty = allow all. */
        private String block = "";
    }

    @Data
    public static class MessageSettings {
        private BlockSettings block = new BlockSettings();
    }
}
