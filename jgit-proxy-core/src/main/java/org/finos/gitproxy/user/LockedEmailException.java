package org.finos.gitproxy.user;

/** Thrown when attempting to remove an email that was locked by an identity provider. */
public class LockedEmailException extends RuntimeException {

    public LockedEmailException(String email) {
        super("Email is locked by identity provider and cannot be removed: " + email);
    }
}
