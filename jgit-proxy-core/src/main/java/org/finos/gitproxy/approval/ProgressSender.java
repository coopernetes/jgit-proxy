package org.finos.gitproxy.approval;

@FunctionalInterface
public interface ProgressSender {
    void send(String message);
}
