package io.github.mbackschat.a12.dm.cli;

import java.util.Collection;
import java.util.LinkedHashSet;

/**
 * The shared merge algorithm for the model's comma-separated {@code roles} header annotation (G26). A
 * {@code --role} / {@code role} edit MERGES into the existing list — dedupe, keep insertion order, keep
 * what's already there — so a one-role edit doesn't clobber the rest (unlike a raw {@code annotation
 * roles=…}). T4: this was duplicated verbatim between the standalone {@code config modify} (on a
 * {@code ModelDocument}) and the {@code apply} {@code CONFIG_MODIFY} op (on an {@code EditSession}); the two
 * receivers differ, but the merge is a pure string function, so it single-sources here.
 */
public final class RolesMerge {

    private RolesMerge() {
    }

    /** The new value of the {@code roles} annotation after merging {@code add} into {@code existing} (may be null). */
    public static String merge(String existing, Collection<String> add) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (existing != null && !existing.isBlank()) {
            for (String role : existing.split(",")) {
                if (!role.isBlank()) {
                    merged.add(role.trim());
                }
            }
        }
        merged.addAll(add);
        return String.join(",", merged);
    }
}
