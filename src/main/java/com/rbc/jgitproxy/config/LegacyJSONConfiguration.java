package com.rbc.jgitproxy.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** Legacy configuration to bring users of the old configuration into the new configuration. */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class LegacyJSONConfiguration {

    private String proxyUrl;
    private String cookieSecret;
    private int sessionMaxAgeHours;
    private TempPassword tempPassword;
    private List<AuthorisedRepo> authorisedList;
    private List<Sink> sink;
    private List<Authentication> authentication;
    private Api api;
    private CommitConfig commitConfig;
    private AttestationConfig attestationConfig;
    private Map<String, Object> domains;
    private List<String> privateOrganizations;
    private String urlShortener;
    private String contactEmail;
    private boolean csrfProtection;
    private List<String> plugins;

    // Getters and setters
    @Data
    public static class TempPassword {
        private boolean sendEmail;
        private Map<String, Object> emailConfig;

        // Getters and setters
    }

    @Data
    public static class AuthorisedRepo {
        private String project;
        private String name;
        private String url;

        // Getters and setters
    }

    @Data
    public static class Sink {
        private String type;
        private boolean enabled;
        private String connectionString;
        private Map<String, Object> options;
        private Map<String, Object> params;

        // Getters and setters
    }

    @Data
    public static class Authentication {
        private String type;
        private boolean enabled;
        private Map<String, Object> options;

        // Getters and setters
    }

    @Data
    public static class Api {
        private Github github;

        // Getters and setters

        @Data
        public static class Github {
            private String baseUrl;

            // Getters and setters
        }
    }

    @Data
    public static class CommitConfig {
        private Author author;
        private Message message;
        private Diff diff;

        // Getters and setters
        @Data
        public static class Author {
            private Email email;

            // Getters and setters

            @Data
            public static class Email {
                private Local local;
                private Domain domain;

                // Getters and setters

                @Data
                public static class Local {
                    private String block;

                    // Getters and setters
                }

                @Data
                public static class Domain {
                    private String allow;

                    // Getters and setters
                }
            }
        }

        @Data
        public static class Message {
            private Block block;

            // Getters and setters
            @Data
            public static class Block {
                private List<String> literals;
                private List<String> patterns;

                // Getters and setters
            }
        }

        @Data
        public static class Diff {
            private Block block;

            // Getters and setters

            @Data
            public static class Block {
                private List<String> literals;
                private List<String> patterns;
                private Map<String, Object> providers;

                // Getters and setters
            }
        }
    }

    @Data
    public static class AttestationConfig {
        private List<Question> questions;

        // Getters and setters

        @Data
        public static class Question {
            private String label;
            private Tooltip tooltip;

            // Getters and setters

            @Data
            public static class Tooltip {
                private String text;
                private List<String> links;

                // Getters and setters
            }
        }
    }
}
