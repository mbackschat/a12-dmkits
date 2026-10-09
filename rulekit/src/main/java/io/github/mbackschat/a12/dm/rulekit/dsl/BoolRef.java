package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * A typed handle to a boolean field. Booleans compare to {@code True}/{@code False} (or another
 * boolean field) only — anything else is {@code MVK_INVALID_COMPARE_TO_YESNO} (verified; see
 * KERNEL-INTEGRATION-NOTES). An <em>unset</em> boolean makes the comparison not-evaluated (§5b) —
 * guard with {@code filled()} when that matters. Obtain from {@link BoundModel#bool(String)}.
 */
public record BoolRef(FieldRef field) {

    /**
     * Marks this reference as the {@code $} outer-iteration correlation ({@code [$path] == True}) — it binds
     * to the enclosing rule iteration, and is legal only inside a {@code Having} filter (D13).
     *
     * <p>The marker rides the underlying {@link FieldRef}, the home every typed reference keeps it in (API-FINDINGS
     * AF181), and every comparison here is built from {@code field.value()}, which moves it onto the value's own
     * flag. This record mirrors it in no flag of its own, since nothing it builds bypasses its field.
     *
     * <p><b>It was absent until 2026-09-05, and the absence was a defect rather than a decision.</b> The
     * kernel admits a correlated boolean comparison in a filter — measured in
     * {@code validate.laws.CorrelatedBooleanComparisonLawsTest} against the catalog's own admitted carrier —
     * so a legal model had no typed shape here, and the read path dropped the marker SILENTLY instead of
     * refusing, which is the sharper half: an uncorrelated comparison reads a different row and renders
     * identically.
     */
    public BoolRef outer() {
        return new BoolRef(field.outer());
    }

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
