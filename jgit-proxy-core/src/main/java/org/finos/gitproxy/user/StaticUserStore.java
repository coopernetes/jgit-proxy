package org.finos.gitproxy.user;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** In-memory {@link UserStore} backed by a static list — suitable for YAML-configured users. */
public class StaticUserStore implements UserStore {

    private final Map<String, UserEntry> byUsername;
    private final Map<String, String> emailIndex;
    private final Map<String, String> pushUsernameIndex;

    public StaticUserStore(List<UserEntry> users) {
        this.byUsername = users.stream().collect(Collectors.toMap(UserEntry::getUsername, Function.identity()));
        this.emailIndex = users.stream()
                .flatMap(u -> u.getEmails().stream().map(email -> Map.entry(email.toLowerCase(), u.getUsername())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
        this.pushUsernameIndex = users.stream()
                .flatMap(u -> u.getPushUsernames().stream().map(pu -> Map.entry(pu, u.getUsername())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
    }

    @Override
    public Optional<UserEntry> findByUsername(String username) {
        return Optional.ofNullable(byUsername.get(username));
    }

    @Override
    public Optional<UserEntry> findByPushUsername(String pushUsername) {
        if (pushUsername == null) return Optional.empty();
        String username = pushUsernameIndex.get(pushUsername);
        return username != null ? findByUsername(username) : Optional.empty();
    }

    @Override
    public Optional<UserEntry> findByEmail(String email) {
        if (email == null) return Optional.empty();
        String username = emailIndex.get(email.toLowerCase());
        return username != null ? findByUsername(username) : Optional.empty();
    }

    @Override
    public List<UserEntry> findAll() {
        return Collections.unmodifiableList(List.copyOf(byUsername.values()));
    }
}
