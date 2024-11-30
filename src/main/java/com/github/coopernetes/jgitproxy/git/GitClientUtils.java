package com.github.coopernetes.jgitproxy.git;

import jakarta.annotation.Nullable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

public class GitClientUtils {

    @RequiredArgsConstructor
    @Getter
    public enum AnsiColor {
        RESET("\u001B[0m"),
        BLACK("\u001B[30m"),
        RED("\u001B[31m"),
        GREEN("\u001B[32m"),
        YELLOW("\u001B[33m"),
        BLUE("\u001B[34m"),
        PURPLE("\u001B[35m"),
        CYAN("\u001B[36m"),
        WHITE("\u001B[37m");

        private final String code;
    }
    /**
     * Returns a message with a title and a message to the git client. It contains
     * no color coding for operations which don't support it.
     *
     * @param title
     * @param message
     * @return
     */
    public static String clientMessage(String title, String message) {
        return "\n\n\t " + title + " \n\n" + message;
    }

    /**
     * Returns a message with a title and a message to the git client. The whole
     * client message will be colored.
     *
     * @param title
     * @param message
     * @param color
     * @return
     */
    public static String clientMessage(String title, String message, AnsiColor color) {
        return color.getCode() + "\n\n\t " + title + " \n\n" + message + AnsiColor.RESET.getCode();
    }

    /**
     * Returns a message with a title and a message to the git client. The title and
     * message will be colored.
     *
     * @param title
     * @param message
     * @param titleColor
     * @param messageColor
     * @return
     */
    public static String clientMessage(
            String title, String message, @Nullable AnsiColor titleColor, @Nullable AnsiColor messageColor) {
        return "\n\n\t " + (titleColor != null ? titleColor.getCode() : "") + title
                + (messageColor == null ? AnsiColor.RESET.getCode() : "") + " \n\n"
                + (messageColor != null ? messageColor.getCode() : "") + message + AnsiColor.RESET.getCode();
    }
}
