package org.finos.gitproxy.user;

/** Thrown when attempting to register an email address already claimed by another proxy user. */
public class EmailConflictException extends RuntimeException {

    private final String owner;

    public EmailConflictException(String email, String owner) {
        super("Email '" + email + "' is already registered to user: " + owner);
        this.owner = owner;
    }

    public String getOwner() {
        return owner;
    }
}
