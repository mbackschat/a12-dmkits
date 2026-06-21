package io.github.mbackschat.a12.dm.cli.field;

import java.util.Optional;

/**
 * Resolve a field address given <b>either</b> a positional {@code <fieldPath>} <b>or</b> a
 * {@code --group} + {@code --name} pair (G19 — accept both forms, so an agent that learned the
 * {@code field add --group/--name} style isn't tripped when reading/removing a field, and vice versa).
 * Single-sourced so {@code field read} and {@code field remove} can't drift on the rule.
 */
final class FieldLocator {

    private FieldLocator() {
    }

    /**
     * The field's full name-path from a positional path OR a {@code --group}/{@code --name} pair. Empty if
     * <b>neither</b> is given (the caller decides — a read lists all, a remove errors). Throws if <b>both</b>
     * a positional and the flags are given, or only one of {@code --group}/{@code --name}.
     */
    static Optional<String> resolve(String positional, String group, String name) {
        boolean hasPath = positional != null && !positional.isBlank();
        boolean hasGroup = group != null && !group.isBlank();
        boolean hasName = name != null && !name.isBlank();
        if (hasPath && (hasGroup || hasName)) {
            throw new IllegalArgumentException("pass EITHER a positional <fieldPath> OR --group + --name, not both");
        }
        if (hasGroup != hasName) {
            throw new IllegalArgumentException("--group and --name go together — the field's group path + its name");
        }
        if (hasPath) {
            return Optional.of(positional);
        }
        if (hasGroup) {
            return Optional.of(group + "/" + name);
        }
        return Optional.empty();
    }
}
