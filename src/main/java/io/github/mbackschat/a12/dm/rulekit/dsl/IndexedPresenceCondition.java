package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * {@code FieldFilled([Field For key])} / {@code FieldNotFilled(...)} — the presence of the cell a
 * semantic index resolves to (KERNEL-SEMANTICS §9 presence). A distinct node from
 * {@link FieldPresenceCondition} because its operand is an {@link IndexedFieldValue}, not a plain
 * {@link FieldRef}; it renders the index BARE (no value brackets — the brackets belong to the value
 * read, {@code [Field For key]}).
 */
public record IndexedPresenceCondition(IndexedFieldValue index, boolean filled) implements Condition {

    @Override
    public String operatorId() {
        return filled ? "FieldFilled" : "FieldNotFilled";
    }

    /**
     * Build a presence over {@code expression} — a fail-fast corrective (memory: prefer clean
     * corrective errors): {@code FieldFilled}/{@code FieldNotFilled} take a field/cell operand, so only
     * an indexed cell reference ({@code forIndex(...)}) has a presence; an arbitrary aggregate or
     * arithmetic expression does not (it would render a kernel-illegal {@code FieldFilled(Sum(...))}).
     */
    static Condition of(Calculation expression, boolean filled) {
        if (expression instanceof IndexedFieldValue index) {
            return new IndexedPresenceCondition(index, filled);
        }
        throw new IllegalArgumentException("filled()/notFilled() is only valid on an indexed field reference"
                + " (forIndex(...)); FieldFilled needs a field/cell operand, not "
                + expression.getClass().getSimpleName());
    }
}
