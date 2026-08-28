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
 *
 * <p>The third arm, {@link IndexedFieldValue} — an index-selected field ({@code [Field For key]}) —
 * behaves like a single field entity at every one of these slots: it never satisfies arity alone the
 * way a starred {@link FieldListRef} does, and it participates in exact-duplicate detection keyed on
 * field path AND index. Adding it here, rather than growing {@link AggregateOperand}
 * a THIRD arm, satisfies both sealed hierarchies at once.
 */
public sealed interface FieldOperand extends ValueListOperand, AggregateOperand
        permits FieldRef, FieldListRef, IndexedFieldValue {

    /**
     * The declared value kind of the referenced field. Declared HERE rather than on {@link ValueListOperand}
     * because it is a property of a <em>field-like</em> operand: {@link ValueListOperand}'s
     * {@link GroupListRef} arm has no kind of its own, since a group's kind belongs to its expansion and is
     * the kernel's verdict (DG35, 2026-08-14 — the same call {@link AggregateOperand} already makes).
     */
    io.github.mbackschat.a12.dm.core.ValueKind kind();

    /** This checked field/list as one compositional {@code FirstFilledValue} owner entity. */
    default FirstFilledOperand firstFilledOperand() {
        return FirstFilledOperand.of(this);
    }
}
