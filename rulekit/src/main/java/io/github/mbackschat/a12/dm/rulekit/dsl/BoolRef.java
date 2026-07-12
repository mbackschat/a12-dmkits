package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * A typed handle to a boolean field. Booleans compare to {@code True}/{@code False} (or another
 * boolean field) only — anything else is {@code MVK_INVALID_COMPARE_TO_YESNO} (verified; see
 * KERNEL-INTEGRATION-NOTES). An <em>unset</em> boolean makes the comparison not-evaluated (§5b) —
 * guard with {@code filled()} when that matters. Obtain from {@link BoundModel#bool(String)}.
 */
public record BoolRef(FieldRef field) {

    public Condition filled() {
        return field.filled();
    }

    public Condition notFilled() {
        return field.notFilled();
    }

    /** {@code [field] == True}. catalog:Equal */
    public Condition isTrue() {
        return new ComparisonCondition(field.value(), CompareOp.EQ, new BoolLiteral(true));
    }

    /** {@code [field] == False}. catalog:Equal */
    public Condition isFalse() {
        return new ComparisonCondition(field.value(), CompareOp.EQ, new BoolLiteral(false));
    }

    /**
     * {@code [field] != True} — the inequality direction, kept distinct from {@link #isFalse()} for
     * faithful round-trip (real models write both). For a <em>filled</em> boolean it coincides with
     * {@code == False}, but an unset boolean leaves either comparison not-evaluated (§5b). catalog:NotEqual
     */
    public Condition isNotTrue() {
        return new ComparisonCondition(field.value(), CompareOp.NE, new BoolLiteral(true));
    }

    /** {@code [field] != False} — the inequality peer of {@link #isTrue()}. catalog:NotEqual */
    public Condition isNotFalse() {
        return new ComparisonCondition(field.value(), CompareOp.NE, new BoolLiteral(false));
    }
}
