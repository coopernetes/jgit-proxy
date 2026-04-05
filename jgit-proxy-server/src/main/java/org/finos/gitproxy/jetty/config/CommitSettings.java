package org.finos.gitproxy.jetty.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Binds the {@code commit:} block in git-proxy.yml. This is the raw YAML DTO — all pattern strings are kept as
 * {@code String} fields and compiled to {@link java.util.regex.Pattern} by {@link JettyConfigurationBuilder} when
 * constructing the core {@link org.finos.gitproxy.config.CommitConfig}.
 */
@Data
public class CommitSettings {

    /**
     * Controls whether commit author/committer emails are verified against the authenticated push user. Options:
     * {@code warn} (default), {@code strict}, {@code off}.
     */
    private String identityVerification = "warn";

    private AuthorSettings author = new AuthorSettings();
    private MessageSettings message = new MessageSettings();
    private DiffSettings diff = new DiffSettings();
    private SecretScanningSettings secretScanning = new SecretScanningSettings();

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
        /** Regex blocking specific local-parts (before @). Empty = allow all. */
        private String block = "";
    }

    @Data
    public static class MessageSettings {
        private BlockSettings block = new BlockSettings();
    }

    @Data
    public static class DiffSettings {
        private BlockSettings block = new BlockSettings();
    }

    @Data
    public static class BlockSettings {
        private List<String> literals = new ArrayList<>();
        private List<String> patterns = new ArrayList<>();
    }

    @Data
    public static class SecretScanningSettings {
        private boolean enabled = false;
        private boolean autoInstall = true;
        private String installDir = "";
        private String version = "";
        private String scannerPath = "";
        private String configFile = "";
        private long timeoutSeconds = 30;
    }
}
