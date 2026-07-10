package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * {@code FieldFilled([Field For key])} / {@code FieldNotFilled(...)} — the presence of the cell a
 * semantic index resolves to (KERNEL-SEMANTICS §9 presence). A distinct node from
 * {@link FieldPresenceCondition} because its operand is an {@link IndexedFieldValue}, not a plain
 * {@link FieldRef}; it renders the index BARE (no value brackets — the brackets belong to the value
 * read, {@code [Field For key]}). Authored via {@link IndexedCell} ({@code ref.indexedCell(key).filled()});
 * the {@code IndexedFieldValue} operand makes the non-cell misuse unrepresentable, so no runtime guard
 * is needed (API-FINDINGS finding 62).
 */
public record IndexedPresenceCondition(IndexedFieldValue index, boolean filled) implements Condition {

    @Override
    public String operatorId() {
        return filled ? "FieldFilled" : "FieldNotFilled";
    }
}
