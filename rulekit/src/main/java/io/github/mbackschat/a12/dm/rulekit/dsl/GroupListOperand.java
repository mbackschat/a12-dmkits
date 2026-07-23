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
 */
public sealed interface GroupListOperand permits FieldRef, GroupListRef {
}
