package org.finos.gitproxy.user;

/** Thrown when attempting to mutate a user that is defined in configuration. */
public class LockedByConfigException extends RuntimeException {

    public LockedByConfigException(String username) {
        super("User '" + username + "' is defined in configuration and cannot be modified at runtime");
    }
}
