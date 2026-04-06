package org.finos.gitproxy.user;

/** Thrown when attempting to claim an SCM identity already registered to another proxy user. */
public class ScmIdentityConflictException extends RuntimeException {

    private final String owner;

    public ScmIdentityConflictException(String provider, String scmUsername, String owner) {
        super("SCM identity '" + provider + "/" + scmUsername + "' is already claimed by user: " + owner);
        this.owner = owner;
    }

    public String getOwner() {
        return owner;
    }
}
