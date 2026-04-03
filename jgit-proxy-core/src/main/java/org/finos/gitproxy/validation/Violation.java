package org.finos.gitproxy.validation;

/**
 * A single validation failure returned by a {@link CommitCheck} or {@link DiffCheck}.
 *
 * <p>{@code subject} is the short identifier (email address, commit SHA, finding rule name, etc.) used for inline
 * sideband output in S&F mode. {@code reason} is a brief human-readable explanation. {@code formattedDetail} is the
 * full ANSI-formatted message stored in the push record and displayed in the dashboard.
 */
public record Violation(String subject, String reason, String formattedDetail) {}
