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

    /**
     * Push usernames — HTTP Basic-auth usernames this user may push as. Allows authorization without SCM OAuth. Add
     * your git client username(s) here (e.g. corporate username, GitHub handle).
     */
    private List<String> pushUsernames = new ArrayList<>();

    /** SCM identities: provider + username pairs (e.g. github/alice). */
    private List<ScmIdentityConfig> scmIdentities = new ArrayList<>();
}
