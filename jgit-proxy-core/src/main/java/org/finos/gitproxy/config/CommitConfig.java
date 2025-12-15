package org.finos.gitproxy.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/** Configuration for commit validation filters. */
@Data
@Builder
public class CommitConfig {

    /** Configuration for author email validation. */
    @Builder.Default
    private AuthorConfig author = AuthorConfig.builder().build();

    /** Configuration for commit message validation. */
    @Builder.Default
    private MessageConfig message = MessageConfig.builder().build();

    /** Configuration for author validation. */
    @Data
    @Builder
    public static class AuthorConfig {

        /** Configuration for email validation. */
        @Builder.Default
        private EmailConfig email = EmailConfig.builder().build();
    }

    /** Configuration for email validation. */
    @Data
    @Builder
    public static class EmailConfig {

        /** Configuration for email domain validation. */
        @Builder.Default
        private DomainConfig domain = DomainConfig.builder().build();

        /** Configuration for email local part validation. */
        @Builder.Default
        private LocalConfig local = LocalConfig.builder().build();
    }

    /** Configuration for email domain validation. */
    @Data
    @Builder
    public static class DomainConfig {

        /**
         * Regex pattern for allowed email domains. If set, only emails matching this pattern are allowed. Example:
         * ".*\\.company\\.com$" to allow only company.com domains.
         */
        private String allow;
    }

    /** Configuration for email local part validation. */
    @Data
    @Builder
    public static class LocalConfig {

        /**
         * Regex pattern for blocked email local parts. If set, emails matching this pattern are blocked. Example:
         * "^(noreply|no-reply)$" to block noreply addresses.
         */
        private String block;
    }

    /** Configuration for commit message validation. */
    @Data
    @Builder
    public static class MessageConfig {

        /** Configuration for blocking specific message patterns. */
        @Builder.Default
        private BlockConfig block = BlockConfig.builder().build();
    }

    /** Configuration for blocking specific message patterns. */
    @Data
    @Builder
    public static class BlockConfig {

        /**
         * List of literal strings that are blocked in commit messages. Messages containing any of these strings will be
         * rejected (case-insensitive).
         */
        @Builder.Default
        private List<String> literals = new ArrayList<>();

        /**
         * List of regex patterns that are blocked in commit messages. Messages matching any of these patterns will be
         * rejected.
         */
        @Builder.Default
        private List<String> patterns = new ArrayList<>();
    }

    /**
     * Create a default configuration with no restrictions.
     *
     * @return A default CommitConfig instance
     */
    public static CommitConfig defaultConfig() {
        return CommitConfig.builder().build();
    }
}
