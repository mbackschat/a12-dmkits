package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * An operand of a fixed group-list quantifier ({@link GroupListPredicate}) — the typed form of the
 * kernel's common <em>entity list</em> (SPEC-2026-07-23-09, KERNEL-FINDINGS §1): despite their names,
 * {@code AllGroupsFilled}/{@code NoGroupFilled}/{@code AtLeastOneGroupFilled}/{@code NotAllGroupsFilled}/
 * {@code GroupsNotCollectivelyFilled} admit BOTH a checked field ({@link FieldRef} — contributing
 * filled / empty / unavailable directly) and a group scope ({@link GroupListRef} — contributing the
 * validation group product state). A bare group is canonically a {@link GroupListRef} with no starred
 * segments ({@link BoundModel#flattenGroup(String)} accepts an unstarred path), so one referent has
 * exactly one representation. Duplicate, overlap, arity, root, and wildcard gates stay
 * kernel-arbitrated (F4) — the typed surface represents the list and lets the kernel refuse.
 *
 * <p>The third arm, {@link IndexedGroupValue} — a group reached through a semantic index — shows
 * these GROUP-fill quantifiers admit an indexed group exactly as they admit a plain one. Duplicate/overlap
 * stays exactly as kernel-arbitrated here as it already is for the plain group arm: {@link
 * GroupListPredicate} pre-checks neither, so {@link IndexedGroupValue#duplicateIdentity()} is exercised
 * only where it already was — the entity-list aggregates ({@code DuplicateGate}), via
 * the {@link AggregateOperand} arm, not this one.
 *
 * <p>The fourth arm, {@link IndexedFieldValue}, is a field reached through a semantic index. A12 admits it where
 * it admits a checked field and reads it as the selected cell's presence (INTERPRETER-FINDINGS IF335).
 */
public sealed interface GroupListOperand extends DuplicateOperand
        permits FieldOperand.Cell, GroupListRef, IndexedGroupValue {
}
