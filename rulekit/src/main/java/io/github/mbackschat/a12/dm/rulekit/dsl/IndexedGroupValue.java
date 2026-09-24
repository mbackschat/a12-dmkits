package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.Optional;

/**
 * A group scope reached through a semantic index — {@code [Group For key]} — the group analog of
 * {@link IndexedFieldValue}: the literal-keyed {@link GroupSemanticIndexValue} or the field-keyed
 * {@link GroupSemanticIndexFieldValue}. Unlike an indexed field, an indexed group carries no VALUE of
 * its own (a group is never a {@link Calculation}) — it exists purely as an
 * entity-list / group-list-quantifier member ({@link AggregateOperand}, {@link GroupListOperand}). Its arity
 * is an unindexed group's: alone it satisfies an entity list's param law, and alone it is refused by
 * {@code AllGroupsFilled}'s (KF351, {@code validate.laws.SemanticIndexGroupOperandCarrierLawsTest}). It
 * participates in exact-duplicate detection keyed on group path AND index, never on path alone.
 *
 * <p>A group can never itself carry the {@code $} outer-iteration correlation ({@link GroupRef} has no
 * such flag, and the interpreter bridge's {@code Operands.groupRef} already refuses one) — only the field-keyed
 * form's KEY can,
 * so the identity's correlation component is the key's alone.
 *
 * <p>No compact-constructor check at all, matching {@link IndexedFieldValue}'s own discipline — arity and
 * duplicates live in {@link #duplicateIdentity()} and the switches it feeds. A star in {@link #group()} is left
 * to the kernel too: A12 admits one below the index group and refuses one on or above it, and which group is
 * the index group is a model fact these records never see ({@code validate.laws.SemanticIndexWildcardPlacementLawsTest}).
 */
public sealed interface IndexedGroupValue extends GroupScope, GroupListOperand
        permits GroupSemanticIndexValue, GroupSemanticIndexFieldValue {

    /** The group scope the index is written on; it may carry a star below the index group (see the class doc). */
    GroupListRef group();

    /** {@code GroupFilled(Group For key)}: the selected row holds content. */
    default Condition filled() {
        return new IndexedGroupPresenceCondition(this, true);
    }

    /** {@code GroupNotFilled(Group For key)}: the selected row holds none, or no row carries the key. */
    default Condition notFilled() {
        return new IndexedGroupPresenceCondition(this, false);
    }

    /**
     * A plain diagnostic path — {@code Group For "key"} / {@code Group For keyField} — via the same
     * {@link SemanticIndexIdentity} helper {@link IndexedFieldValue} uses. The group itself never
     * carries {@code $} (see class javadoc), so its correlation flag is always {@code false} here.
     */
    @Override
    default String renderedPath() {
        return switch (this) {
            case GroupSemanticIndexValue v -> SemanticIndexIdentity.of(false, group().renderedPath(),
                    v.indexValue());
            case GroupSemanticIndexFieldValue v -> SemanticIndexIdentity.of(false, group().renderedPath(),
                    v.indexField());
        };
    }

    /**
     * The compound identity for exact-duplicate detection ({@code MVK_DUPLICATE_PARAM1}) — group path,
     * index key, and the key's own {@code $} correlation if field-keyed. Shared with {@link
     * IndexedFieldValue#duplicateIdentity()}'s caller ({@code DuplicateGate})
     * rather than a second, independent identity switch. Deliberately excludes the indirect-overlap
     * case ({@code MVK_DUPLICATE_PARAM2} — an indexed field overlapping an indexed group over the same
     * repetition): that stays kernel-arbitrated, exactly as the plain group arm's own indirect-overlap
     * case already is.
     */
    default String duplicateIdentity() {
        return renderedPath();
    }

    @Override
    default Optional<String> exactIdentity() {
        return Optional.of(duplicateIdentity());
    }

    /** The base group this index selects a repetition of — the path the containment relation uses. */
    @Override
    default String fieldPath() {
        return group().fieldPath();
    }

    /** See {@link IndexedFieldValue#overlapModifier()}. */
    @Override
    default String overlapModifier() {
        return switch (this) {
            case GroupSemanticIndexValue v -> "For:" + v.indexValue();
            case GroupSemanticIndexFieldValue v -> "For:" + (v.indexField().correlated() ? "$" : "")
                    + v.indexField().renderedPath();
        };
    }
}
