package io.github.mbackschat.a12.rulekit.dsl;

/**
 * A typed handle to a confirm field. Confirm is a distinct kind from Boolean (§5b): it compares
 * only to {@code True} (or another confirm field) — {@code == False} is
 * {@code MVK_INVALID_COMPARE_TO_YES} (verified; see KERNEL-INTEGRATION-NOTES). This handle makes
 * that mistake unrepresentable: there is deliberately no {@code isFalse()} — "not confirmed" is
 * {@link #notConfirmed()} ({@code != True}; an unset confirm counts as not confirmed). Obtain from
 * {@link BoundModel#confirm(String)}.
 */
public record ConfirmRef(FieldRef field) {

    public Condition filled() {
        return field.filled();
    }

    public Condition notFilled() {
        return field.notFilled();
    }

    /** {@code [field] == True}. */
    public Condition confirmed() {
        return new ComparisonCondition(field.value(), CompareOp.EQ, new BoolLiteral(true));
    }

    /** {@code [field] != True} — the kernel-legal "not confirmed" (no {@code == False} for confirm). */
    public Condition notConfirmed() {
        return new ComparisonCondition(field.value(), CompareOp.NE, new BoolLiteral(true));
    }
}
