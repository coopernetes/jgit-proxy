package com.github.coopernetes.jgitproxy.git;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

public class GitClient {

    /**
     * ANSI color codes used to display colored messages in the console. Note that not all clients support ANSI color.
     * Certain client interactions (git fetch) may also restrict what is displayed.
     */
    @RequiredArgsConstructor
    @Getter
    public enum AnsiColor {
        RESET("\u001B[0m"),
        BLACK("\u001B[30m"),
        RED("\u001B[31m"),
        GREEN("\u001B[32m"),
        YELLOW("\u001B[33m"),
        BLUE("\u001B[34m"),
        MAGENTA("\u001B[35m"),
        CYAN("\u001B[36m"),
        WHITE("\u001B[37m");

        private final String value;

        @Override
        public String toString() {
            return value;
        }
    }

    /**
     * List of Unicode codepoints used to display symbols and/or emojis in the client response. Note that not all
     * clients support the same set of chracters. Certain client interactions (git fetch) may also restrict what is
     * displayed.
     */
    @RequiredArgsConstructor
    @Getter
    public enum SymbolCodes {
        // https://codepoints.net/U+26D4
        // https://emojipedia.org/no-entry#technical
        NO_ENTRY("\u26D4"),

        // https://codepoints.net/U+2705
        // https://emojipedia.org/check-mark-button#technical
        CHECK_MARK("\u2705"),

        // https://codepoints.net/U+26A0
        // https://emojipedia.org/warning#technical
        WARNING("\u26A0"),

        // Created using codepoints & the Character class due to the point values
        // falling outside the Unicode planes' range supported by Java's \\u escape
        // sequence syntax in Strings

        // https://codepoints.net/U+1F511
        // https://emojipedia.org/key#technical
        KEY(Character.toString(0x1F511)),

        // https://codepoints.net/U+1F517
        // https://emojipedia.org/link#technical
        LINK(Character.toString(0x1F517));

        private final String value;

        /**
         * Returns the emoji representation of the symbol by appending the Unicode variation selector character. This is
         * used to ensure that the symbol is displayed as an emoji in clients that support it.
         *
         * @return the emoji representation of the symbol
         */
        public String emoji() {
            return value + "\uFE0F";
        }

        /**
         * Returns the plain text representation of the symbol by appending the Unicode variation selector character.
         * This is used to ensure that the symbol is displayed as plain text in clients that don't support emojis.
         *
         * @return the plain text representation of the symbol
         */
        public String plain() {
            return value + "\uFE0E";
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /**
     * Returns a message with a title and a message to the git client. It contains no color coding for operations which
     * don't support it such as fetch.
     *
     * @param title the title to display
     * @param message the message to display
     * @return the formatted message for the git client to display
     */
    public static String format(String title, String message) {
        return formatTitle(title) + formatMessage(message);
    }

    /**
     * Returns a message with a title and a message to the git client. The whole client message will be colored.
     *
     * @param title the title to display
     * @param message the message to display
     * @param color the color to use for the message
     * @return the formatted message for the git client to display with color. the last line will be reset to default
     *     color
     */
    public static String format(String title, String message, AnsiColor color) {
        return (color != null ? color : "") + format(title, message) + (color != null ? AnsiColor.RESET : "");
    }

    /**
     * Returns a message with a title and a message to the git client. The title and message will be colored.
     *
     * @param title the title to display
     * @param message the message to display
     * @param titleColor the color to use for the message title or {@code null} to use the default color
     * @param messageColor the color to use for the message content or {@code null} to use the default color
     * @return the formatted message for the git client to display with color. the last line will be reset to default
     *     color
     */
    public static String format(String title, String message, AnsiColor titleColor, AnsiColor messageColor) {
        String formattedTitle = (titleColor != null ? titleColor.getValue() : "") + formatTitle(title);
        String formattedMessage = (messageColor != null ? messageColor.getValue() : AnsiColor.RESET)
                + formatMessage(message)
                + AnsiColor.RESET;
        return formattedTitle + formattedMessage;
    }

    /**
     * Returns a message with a title and a message to the git client. If the operation is a Fetch, it converts matching
     * symbols from emoji to plain format and removes any color codes. If it's a Push, it doesn't modify the message or
     * title content.
     *
     * @param title the title to display
     * @param message the message to display
     * @param operation the git operation (Fetch or Push)
     * @return the formatted message for the git client to display
     */
    public static String formatForOperation(String title, String message, AnsiColor color, HttpOperation operation) {
        if (operation == HttpOperation.FETCH) {
            title = convertSymbolsToPlain(title);
            title = stripColors(title);
            message = convertSymbolsToPlain(message);
            message = stripColors(message);
            return format(title, message);
        } else {
            return format(title, message, color);
        }
    }

    /**
     * Returns a message with a title and a message to the git client. If the operation is a Fetch, it converts matching
     * symbols from emoji to plain format and removes any color codes. If it's a Push, it doesn't modify the message or
     * title.
     *
     * @param title the title to display
     * @param message the message to display
     * @param operation the git operation (Fetch or Push)
     * @return the formatted message for the git client to display
     */
    public static String formatForOperation(
            String title, String message, AnsiColor titleColor, AnsiColor messageColor, HttpOperation operation) {
        if (operation == HttpOperation.FETCH) {
            title = convertSymbolsToPlain(title);
            title = stripColors(title);
            message = convertSymbolsToPlain(message);
            message = stripColors(message);
            return format(title, message);
        } else {
            return format(title, message, titleColor, messageColor);
        }
    }

    private static String formatTitle(String content) {
        return "\n\n\t" + content;
    }

    private static String formatMessage(String content) {
        return "\n\n" + content;
    }

    private static String convertSymbolsToPlain(String content) {
        for (var color : SymbolCodes.values()) {
            content = content.replace(color.emoji(), color.plain());
        }
        return content;
    }

    private static String stripColors(String content) {
        for (var color : AnsiColor.values()) {
            content = content.replace(color.getValue(), "");
        }
        return content;
    }
}
