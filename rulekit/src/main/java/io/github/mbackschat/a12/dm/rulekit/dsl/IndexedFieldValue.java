package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.Optional;

/**
 * A field value reached through a semantic index — {@code [Field For key]} (KERNEL-SEMANTICS §9): the
 * literal-keyed {@link SemanticIndexValue} or the field-keyed {@link SemanticIndexFieldValue}. Beyond
 * being a comparable {@link Calculation} value ({@code ref.forIndex(key)}), an indexed cell has a
 * <em>presence</em> — so it grows {@code FieldFilled}/{@code FieldNotFilled} via
 * {@link IndexedPresenceCondition}, authored through the dedicated {@link SemanticIndex} handle
 * ({@code ref.semanticIndex(key).filled()} / {@code .notFilled()}).
 *
 * <p>Also a {@link FieldOperand}: an index-selected field behaves like a single field
 * entity in every entity-list slot (an aggregate, a presence quantifier, a value-list membership check, a
 * group-list quantifier, which A12 reads as the selected cell's presence)
 * — it never satisfies arity alone the way a starred {@link FieldListRef} does, and it participates in
 * exact-duplicate detection keyed on field path AND index, never on path alone. No compact-constructor
 * check is added for this: the two identity switches that already exist for {@link FieldRef} are where
 * that gate lives, widened to this type via {@link #duplicateIdentity()}.
 */
public sealed interface IndexedFieldValue extends Calculation, FieldOperand.Cell
        permits SemanticIndexValue, SemanticIndexFieldValue {

    /** Literal membership of this selected cell; the kernel settles the declared-kind domain (DG71). */
    default Condition in(String first, String... rest) {
        return new ValueListCondition(this, true, StringRef.stringValues(first, rest));
    }

    /** Literal non-membership of this selected cell; see {@link #in(String, String...)}. */
    default Condition notIn(String first, String... rest) {
        return new ValueListCondition(this, false, StringRef.stringValues(first, rest));
    }

    /** The field whose indexed cell this reads. */
    FieldRef field();

    /** The catalog operator-id — {@code SemanticIndex} for both key forms. */
    String operatorId();

    /** This indexed cell as one compositional {@code FirstFilledValue} owner entity. */
    default FirstFilledOperand firstFilledOperand() {
        return FirstFilledOperand.of(this);
    }

    /**
     * A plain diagnostic path — {@code Field For "key"} / {@code Field For keyField} — folding in the
     * {@code $} correlation on the field the same way {@link FieldRef#renderedPath()} does not (that
     * method is path-only; the fold happens at every CALL site that cares, exactly as it does for a
     * plain {@link FieldRef} elsewhere in this package). Like every other arm's {@code renderedPath()}
     * this is not localized and does not need to match {@link DslRenderer}'s actual condition-text
     * output for this type ({@code DslRenderer}'s private {@code indexBody} owns that). Computed via
     * {@link SemanticIndexIdentity}, shared with {@link IndexedGroupValue}'s identical-shaped identity.
     */
    @Override
    default String renderedPath() {
        return switch (this) {
            case SemanticIndexValue v -> SemanticIndexIdentity.of(field().correlated(), field().renderedPath(),
                    v.indexValue());
            case SemanticIndexFieldValue v -> SemanticIndexIdentity.of(field().correlated(), field().renderedPath(),
                    v.indexField());
        };
    }

    @Override
    default String fieldPath() {
        return field().fieldPath();
    }

    /**
     * The compound identity used for exact-duplicate detection ({@code MVK_DUPLICATE_PARAM1}) — field
     * path, index key, and every {@code $} correlation in the shape (the field's own, and — for the
     * field-keyed form — the key field's own too, since a correlated key reads a different repetition
     * than an uncorrelated one and the two must stay distinguishable). Read by {@code DuplicateGate}
     * through {@link #exactIdentity()}, so the compound computation lives in one place. The
     * indirect-overlap arm ({@code MVK_DUPLICATE_PARAM2} — an indexed field reached once directly and
     * once through a group scope) is not this key's business: it compares {@link #overlapModifier()}
     * against the path relation instead, because an index selection removes an overlap that the same
     * scope starred would have.
     *
     * <p>Identical to {@link #renderedPath()} today — two references that print the same diagnostic
     * path ARE the same identity — so this delegates rather than re-computing the same string; its own
     * name and javadoc exist because the gate cares about a dedup key, not a diagnostic string,
     * and that distinction is worth keeping even where the values happen to coincide.
     */
    default String duplicateIdentity() {
        return renderedPath();
    }

    @Override
    default Optional<String> exactIdentity() {
        return Optional.of(duplicateIdentity());
    }

    /**
     * The index selection participates in containment: an index-selected ancestor does not contain a
     * plainly-addressed descendant (kernel-measured, KERNEL-FINDINGS {@code kf439}).
     *
     * <p>The key field's own {@code $} is folded in, for the same reason the EXACT arm folds it: a correlated
     * key reads a different repetition than an uncorrelated one, and the kernel compares that flag. The fold
     * changes no verdict today, because a correlated index key is refused before it can stand beside an
     * uncorrelated twin — {@code MVK_INVALID_OUTER_ITERATION} at a plain rule locus (measured, KERNEL-FINDINGS
     * {@code kf439}). It is kept for symmetry rather than for a reachable case, so a mutant that removes it
     * is expected to survive; omitting it is the direction that would be broader than the kernel if the
     * shape ever became authorable.
     */
    @Override
    default String overlapModifier() {
        return switch (this) {
            case SemanticIndexValue v -> "For:" + v.indexValue();
            case SemanticIndexFieldValue v -> "For:" + (v.indexField().correlated() ? "$" : "")
                    + v.indexField().renderedPath();
        };
    }
}
