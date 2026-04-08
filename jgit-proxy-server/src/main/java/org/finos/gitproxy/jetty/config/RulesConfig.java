package org.finos.gitproxy.jetty.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** Binds the {@code rules:} block in git-proxy.yml. */
@Data
public class RulesConfig {

    /** Allow rules — requests must match at least one entry to proceed. */
    private List<RuleConfig> allow = new ArrayList<>();

    /**
     * Deny rules — requests matching any entry are blocked, even if they also match an allow rule. Deny takes
     * precedence over allow.
     */
    private List<RuleConfig> deny = new ArrayList<>();
}
