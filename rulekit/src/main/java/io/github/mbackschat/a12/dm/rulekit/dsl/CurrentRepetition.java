package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code CurrentRepetition(group)} — the group's current iteration index, as a numeric expression
 * (the kernel's {@code groupOp}; DE {@code AktuelleWiederholung}). Takes a {@link GroupRef} (a field
 * argument is {@code MVK_NO_FIELDS_ALLOWED} — unrepresentable here). The rule must iterate over that
 * group, else {@code MVK_NO_WILDCARD} — position-dependent, so kernel-delegated (KERNEL-FINDINGS §9).
 * A non-repeatable group is tolerated (its index is constant). With {@link #outer} set, the named
 * group selects its own coordinate from the complete outer repetition environment captured by a
 * {@code Having}; parent and descendant paths may therefore resolve to different indices.
 */
public record CurrentRepetition(GroupRef group, boolean outer) implements Calculation {

    /** A non-correlated current-repetition index. */
    public CurrentRepetition(GroupRef group) {
        this(group, false);
    }

    public String operatorId() {
        return "CurrentRepetition";
    }

    @Override
    public ValueKind kind() {
        return ValueKind.NUMBER;
    }
}
