package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code CurrentRepetition(group)} — the group's current iteration index, as a numeric expression
 * (the kernel's {@code groupOp}; DE {@code AktuelleWiederholung}). Takes a {@link GroupRef} (a field
 * argument is {@code MVK_NO_FIELDS_ALLOWED} — unrepresentable here). The rule must iterate over that
 * group, else {@code MVK_NO_WILDCARD} — position-dependent, so kernel-delegated (KERNEL-FINDINGS §9).
 * A non-repeatable group is tolerated (its index is constant).
 */
public record CurrentRepetition(GroupRef group) implements Calculation {

    public String operatorId() {
        return "CurrentRepetition";
    }

    @Override
    public ValueKind kind() {
        return ValueKind.NUMBER;
    }
}
