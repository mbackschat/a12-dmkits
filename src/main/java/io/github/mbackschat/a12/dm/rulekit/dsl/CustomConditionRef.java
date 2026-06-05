package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * {@code CustomCondition <Name>} — the app-defined escape hatch (DE {@code ApplikationsBedingung};
 * KERNEL-SEMANTICS §14): the kernel parses and places the rule but delegates the fire/don't-fire
 * decision to host code registered under {@code name}. No operands — just a bare identifier.
 *
 * <p>Two contexts forbid it (verified 2026-06-04): inside a {@code Having} filter
 * ({@code MVK_DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION}, pre-checked in {@link FieldListRef#having})
 * and in computation rules (pre-checked in {@code Computation.build}). Like any predicate it does
 * not satisfy the error-field rule on its own — pair it with a field reference.
 */
public record CustomConditionRef(String name) implements Condition {

    public CustomConditionRef {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("CustomCondition needs a non-blank name");
        }
        if (!name.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("CustomCondition name must be a bare identifier"
                    + " (letter, then letters/digits/underscores): " + name);
        }
    }

    @Override
    public String operatorId() {
        return "CustomCondition";
    }
}
