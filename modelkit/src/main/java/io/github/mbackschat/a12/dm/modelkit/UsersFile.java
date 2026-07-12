package io.github.mbackschat.a12.dm.modelkit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * The workspace {@code users.yaml} access-control config (FEATURE-GAPS R4) — the seed users and the roles
 * each holds. Where {@link RolesFile} declares the roles, this declares who has them: a {@code users:} list of
 * {@code {username, authorities[], …}}, where {@code authorities} are role names — <b>the membership edge of
 * the RBAC triangle</b>, the other cross-file reference into {@code roles.yaml} (an authority a user holds
 * should be a role the roles file declares).
 *
 * <p>Parsed as a Jackson tree (no reflective POJO binding — the same tree-walking style as {@link RolesFile}/
 * {@link ModelDocument}), kernel-free. Supports both real conventions: a single {@code auth/users.yaml} with a
 * {@code users:} list (the SME), and one flat file per user (the project template's {@code auth/users/}
 * directory) via {@link #parseUser}. Tolerant of an absent/empty {@code authorities}; a document that isn't a
 * mapping is rejected rather than silently read as empty.
 */
public record UsersFile(List<User> users) {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public UsersFile {
        users = List.copyOf(users);
    }

    /** One user: their {@code username} and the role names ({@code authorities}) they hold. */
    public record User(String username, List<String> authorities) {
        public User {
            authorities = List.copyOf(authorities);
        }
    }

    /**
     * Parse a {@code users.yaml} (the {@code users:} list form). A document without a {@code users:} key
     * yields an empty user set.
     *
     * @throws IllegalArgumentException if the content isn't a YAML mapping or can't be parsed as YAML
     */
    public static UsersFile parse(String yaml) {
        JsonNode root = readMapping(yaml, "users.yaml");
        List<User> users = new ArrayList<>();
        for (JsonNode u : root.path("users")) {
            toUser(u).ifPresent(users::add);
        }
        return new UsersFile(List.copyOf(users));
    }

    /**
     * Parse one flat per-user document (the {@code auth/users/<name>.yaml} convention — {@code username} +
     * {@code authorities} at the root, no {@code users:} wrapper).
     *
     * @throws IllegalArgumentException if the content isn't a YAML mapping with a {@code username}
     */
    public static User parseUser(String yaml) {
        return toUser(readMapping(yaml, "user file"))
                .orElseThrow(() -> new IllegalArgumentException("user file has no `username`"));
    }

    /** The role names held across all users, in first-seen order (deduped). */
    public Set<String> authorities() {
        Set<String> out = new LinkedHashSet<>();
        users.forEach(u -> out.addAll(u.authorities()));
        return out;
    }

    /** True iff some user holds {@code role} — the basis for the reachability / orphan-role checks. */
    public boolean anyoneHolds(String role) {
        return users.stream().anyMatch(u -> u.authorities().contains(role));
    }

    /** The user of that name, if any. */
    public Optional<User> user(String username) {
        return users.stream().filter(u -> u.username().equals(username)).findFirst();
    }

    private static Optional<User> toUser(JsonNode node) {
        String username = node.path("username").asText("");
        if (username.isBlank()) {
            return Optional.empty(); // a nameless entry can't be referenced
        }
        List<String> authorities = new ArrayList<>();
        for (JsonNode a : node.path("authorities")) {
            authorities.add(a.asText());
        }
        return Optional.of(new User(username, List.copyOf(authorities)));
    }

    private static JsonNode readMapping(String yaml, String what) {
        JsonNode root;
        try {
            root = YAML.readTree(yaml);
        } catch (Exception e) {
            throw new IllegalArgumentException("not a parseable " + what + ": " + e.getMessage(), e);
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            return YAML.createObjectNode(); // an empty file declares nothing
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException(what + " must be a mapping, not "
                    + root.getNodeType().toString().toLowerCase());
        }
        return root;
    }
}
