package org.finos.gitproxy.jetty.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** Binds a single entry in the {@code users:} list in git-proxy.yml. */
@Data
public class UserConfig {
    private String username = "";

    /** BCrypt password hash. Generate with any BCrypt tool; 12 rounds recommended. */
    private String passwordHash = "";

    /** Email addresses linked to this user (used for commit-author matching). */
    private List<String> emails = new ArrayList<>();

    /** SCM identities: provider + username pairs (e.g. github/alice). */
    private List<ScmIdentityConfig> scmIdentities = new ArrayList<>();

    /**
     * HTTP Basic-auth usernames that are accepted for this proxy user. Allows a user to push under an alias (e.g.
     * {@code push-usernames: [me, alice]}). The proxy {@code username} is always implicitly valid; entries here are
     * additional aliases only.
     */
    private List<String> pushUsernames = new ArrayList<>();
}
