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
 * <p>The scope may carry the {@code $} capture marker ({@link GroupListRef#outer()}), and the marker stays in the
 * rendering and in the identity. A12 refuses a semantic index anywhere in a filter body, which is the only place
 * a capture is read, so such an operand is never admitted; handing A12 the text as authored is what lets it say
 * so, where dropping the marker would author a different operand ({@code adapter.laws.CheckerOverlapIdentityAgreementTest}).
 * <i>Corrected 2026-10-09:</i> this said a group can never carry the marker, which stopped holding once a group
 * scope could (DEV-API-GAPS {@code DG49}), and the marker was dropped.
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
     * {@link SemanticIndexIdentity} helper {@link IndexedFieldValue} uses, with the scope's capture marker.
     */
    @Override
    default String renderedPath() {
        return switch (this) {
            case GroupSemanticIndexValue v -> SemanticIndexIdentity.of(group().correlated(), group().renderedPath(),
                    v.indexValue());
            case GroupSemanticIndexFieldValue v -> SemanticIndexIdentity.of(group().correlated(),
                    group().renderedPath(), v.indexField());
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
