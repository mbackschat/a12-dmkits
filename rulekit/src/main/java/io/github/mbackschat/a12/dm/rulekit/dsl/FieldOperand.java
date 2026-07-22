package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * A field-typed operand for <em>list slots</em> (presence quantifiers, count/numeric aggregates,
 * uniqueness, date-range overlap, and value-list quantifiers): either a plain {@link FieldRef} or a
 * flattened repetition list ({@link FieldListRef}, the {@code Group*}{@code /Field} syntax). Scalar
 * slots keep taking {@link FieldRef} directly — that type split is what erases
 * {@code MVK_NO_WILDCARDS_ALLOWED} (verified 2026-06-04, KERNEL-FINDINGS §9).
 *
 * <p>A field operand is also a {@link ValueListOperand}; the value-list quantifiers accept the wider
 * type so they can additionally take an enum category projection ({@link EnumCategoryAccess}) — which
 * is NOT a {@code FieldOperand}, so it cannot leak into the other slots the kernel forbids it in.
 */
public sealed interface FieldOperand extends ValueListOperand permits FieldRef, FieldListRef {
}
