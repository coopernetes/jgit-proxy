package com.rbc.fogwall.scmapi;

/**
 * Thrown when a GraphQL-over-HTTP request body cannot be parsed — either the outer JSON envelope is malformed, or the
 * {@code query} field is not syntactically valid GraphQL.
 *
 * <p>Callers must treat this as a deny, not a pass-through: a document fogwall cannot parse must not reach upstream
 * unchecked (fail-closed, per docs/internals/SCM_API_PROXY.md).
 */
public class GraphQlParseException extends RuntimeException {

    public GraphQlParseException(String message) {
        super(message);
    }

    public GraphQlParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
