package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * A group scope reached through a semantic index — {@code [Group For key]} — the group analog of
 * {@link IndexedFieldValue}: the literal-keyed {@link GroupSemanticIndexValue} or the field-keyed
 * {@link GroupSemanticIndexFieldValue}. Unlike an indexed field, an indexed group carries no VALUE of
 * its own (a group is never a {@link Calculation}) — it exists purely as an
 * entity-list / group-list-quantifier member ({@link AggregateOperand}, {@link GroupListOperand}),
 * behaving like a single group entity there exactly as an indexed field behaves like a single field
 * entity: it never satisfies arity alone the way a starred {@link GroupListRef} does, and it
 * participates in exact-duplicate detection keyed on group path AND index, never on path alone.
 *
 * <p>A group can never itself carry the {@code $} outer-iteration correlation ({@link GroupRef} has no
 * such flag, and {@link Operands#groupRef} already refuses one) — only the field-keyed form's KEY can,
 * so the identity's correlation component is the key's alone.
 *
 * <p>No compact-constructor check for arity or duplicates, matching {@link IndexedFieldValue}'s own
 * discipline — those gates live in {@link #duplicateIdentity()} and the switches it feeds. The one
 * check both leaf records DO carry is {@link BoundModel#requireExactSemanticIndexGroup} — an index and
 * a wildcard can never address the same group, reusing the exact guard {@code firstFilledGroupFor}
 * already established for this construct's {@code FirstFilledValue} arm, not a new invention.
 */
public sealed interface IndexedGroupValue extends AggregateOperand, GroupListOperand
        permits GroupSemanticIndexValue, GroupSemanticIndexFieldValue {

    /** The group scope this index selects one repetition of — never itself starred/flattened. */
    GroupListRef group();

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
     * IndexedFieldValue#duplicateIdentity()}'s callers ({@code ListAggregate.checkNoDuplicates})
     * rather than a second, independent identity switch. Deliberately excludes the indirect-overlap
     * case ({@code MVK_DUPLICATE_PARAM2} — an indexed field overlapping an indexed group over the same
     * repetition): that stays kernel-arbitrated, exactly as the plain group arm's own indirect-overlap
     * case already is.
     */
    default String duplicateIdentity() {
        return renderedPath();
    }
}
