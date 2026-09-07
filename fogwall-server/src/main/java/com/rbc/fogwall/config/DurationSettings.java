package com.rbc.fogwall.config;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the friendly duration strings YAML config accepts, so every key that takes one reads them the same way.
 *
 * <p>An operator writing {@code 7d} in one section and {@code 90d} in another should not have to discover that only one
 * of them accepts the shorthand.
 */
public final class DurationSettings {

    private static final Pattern FRIENDLY_DURATION = Pattern.compile("(?i)^(\\d+)\\s*([dhms])$");

    private DurationSettings() {}

    /**
     * Parses {@code 90d}, {@code 12h}, {@code 30m}, {@code 45s} or an ISO-8601 duration ({@code PT…}). Returns
     * {@code null} for a blank value, leaving the caller to apply its own default.
     *
     * @param keyName the config key, named in the error so an operator knows which value to fix
     * @throws IllegalArgumentException if the value is non-blank and unparseable
     */
    public static Duration parse(String raw, String keyName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        Matcher m = FRIENDLY_DURATION.matcher(value);
        if (m.matches()) {
            long n = Long.parseLong(m.group(1));
            return switch (Character.toLowerCase(m.group(2).charAt(0))) {
                case 'd' -> Duration.ofDays(n);
                case 'h' -> Duration.ofHours(n);
                case 'm' -> Duration.ofMinutes(n);
                default -> Duration.ofSeconds(n);
            };
        }
        try {
            return Duration.parse(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Invalid " + keyName + " '" + raw
                            + "' — use e.g. 90d, 12h, 30m, or an ISO-8601 duration like PT48H",
                    e);
        }
    }
}
