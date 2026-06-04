package io.github.mbackschat.a12.rulekit.dsl;

import io.github.mbackschat.a12.rulekit.model.ValueKind;

/**
 * A field-typed operand for <em>list slots</em> (quantifiers, shape-B value lists, count
 * aggregates): either a plain {@link FieldRef} or a flattened repetition list
 * ({@link FieldListRef}, the {@code Group*}{@code /Field} syntax). Scalar slots keep taking
 * {@link FieldRef} directly — that type split is what erases {@code MVK_NO_WILDCARDS_ALLOWED}
 * (verified 2026-06-04, KERNEL-FINDINGS §9).
 */
public sealed interface FieldOperand permits FieldRef, FieldListRef {

    /** The rendered path — with {@code *} on flattened segments for a {@link FieldListRef}. */
    String renderedPath();

    /** The base field's path (no stars) — what the operand references, for F9/error-field checks. */
    String fieldPath();

    ValueKind kind();
}
