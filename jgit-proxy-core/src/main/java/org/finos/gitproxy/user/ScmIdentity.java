package org.finos.gitproxy.user;

import lombok.Builder;
import lombok.Value;

/** An SCM identity linking a proxy user to their username on a specific provider. */
@Value
@Builder
public class ScmIdentity {
    /** Provider name (e.g. {@code github}, {@code gitlab}). */
    String provider;

    /** Username on that provider. */
    String username;
}
