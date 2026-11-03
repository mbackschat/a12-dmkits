package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * {@code CustomCondition <Name>} — the app-defined escape hatch (DE {@code ApplikationsBedingung};
 * KERNEL-SEMANTICS §14): the kernel parses and places the rule but delegates the fire/don't-fire
 * decision to host code registered under {@code name}. No operands — just an A12
 * {@code nameOhnePunkt}: letters (including the grammar's German letters), digits, underscore, or
 * colon. A name that collides with a reserved token is quoted by {@link DslRenderer}.
 *
 * <p>Two contexts forbid it (verified 2026-06-04): inside a {@code Having} filter
 * ({@code MVK_DISALLOWED_CONSTRUCT_IN_SET_EXPRESSION}, pre-checked in {@link FieldListRef#having})
 * and in computation rules (pre-checked in {@code Computation.build}). Like any predicate it does
 * not satisfy the error-field rule on its own — pair it with a field reference.
 */
public record CustomConditionRef(String name) implements Condition {

    public CustomConditionRef {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("CustomCondition needs a non-blank name");
        }
        if (!name.matches("[A-Za-z0-9_ÄÜÖäüöß:]+")) {
            throw new IllegalArgumentException("CustomCondition name must use the A12 name alphabet"
                    + " (letters, digits, underscore, or colon): " + name);
        }
    }

    @Override
    public String operatorId() {
        return "CustomCondition";
    }
}
