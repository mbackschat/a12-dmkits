package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.Refusals;
import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * One A12 {@code entitySpec} operand of {@code FirstFilledValue}. The owner shape is compositional: one
 * field or group target may additionally carry a category projection, a semantic index, a wildcard filter,
 * and outer-iteration correlation. Keeping those modifiers on one immutable value prevents one modifier
 * from erasing another during read-back or rendering.
 */
public final class FirstFilledOperand implements DuplicateOperand {

    /** The key of an optional owner semantic-index modifier. */
    public sealed interface SemanticIndexKey permits LiteralIndex, FieldIndex { }

    /** A literal semantic-index key. */
    public record LiteralIndex(String value) implements SemanticIndexKey {
        public LiteralIndex {
            Objects.requireNonNull(value, "value");
        }
    }

    /** A field-valued semantic-index key. */
    public record FieldIndex(FieldRef field) implements SemanticIndexKey {
        public FieldIndex {
            Objects.requireNonNull(field, "field");
        }
    }

    private final FieldOperand field;
    private final GroupListRef group;
    private final List<FieldRef> expandedGroupFields;
    /** Whether the group's path crosses a repeatable level, itself or an ancestor, without its star. A
     *  {@link GroupListRef} records only the starred levels, so only {@link BoundModel} can tell. */
    private final boolean unstarredRepetition;
    private final String category;
    private final SemanticIndexKey semanticIndex;
    private final boolean outer;

    private FirstFilledOperand(
            FieldOperand field,
            GroupListRef group,
            List<FieldRef> expandedGroupFields,
            boolean unstarredRepetition,
            String category,
            SemanticIndexKey semanticIndex,
            boolean outer) {
        if ((field == null) == (group == null)) {
            throw new IllegalArgumentException("a FirstFilledValue operand needs exactly one field or group target");
        }
        this.field = field;
        this.group = group;
        this.expandedGroupFields = List.copyOf(expandedGroupFields);
        this.unstarredRepetition = unstarredRepetition;
        this.category = category;
        this.semanticIndex = semanticIndex;
        this.outer = outer;
        if (group != null && this.expandedGroupFields.isEmpty()) {
            // An empty ROOT group has a class of its own, and like the other shape classes it outranks a
            // duplicate (validate.laws.FirstFilledValueGateOrderLawsTest).
            throw groupShapeRefusal(groupClassIsFixed(),
                    rootGroup() ? KernelCode.MVK_ROOT_GROUP_IS_EMPTY : KernelCode.MVK_GROUP_IS_EMPTY,
                    "FirstFilledValue group " + group.renderedPath() + " must contain at least one field");
        }
    }

    /**
     * A field, flattened field-list, or index-selected field owner entity. The index-selected arm
     * ({@link IndexedFieldValue}) routes to the dedicated {@link #of(IndexedFieldValue)} factory, which
     * already builds the plain field entity and layers the semantic-index modifier on — no new logic
     * here, just a route to code that already exists.
     */
    public static FirstFilledOperand of(FieldOperand field) {
        Objects.requireNonNull(field, "field");
        return switch (field) {
            case IndexedFieldValue indexed -> of(indexed);
            case FieldRef ref ->
                    new FirstFilledOperand(withoutOuter(ref), null, List.of(), false, null, null, ref.correlated());
            case FieldListRef list ->
                    new FirstFilledOperand(withoutOuter(list), null, List.of(), false, null, null, list.correlated());
        };
    }

    /**
     * A group entity, built whole: {@code semanticIndex} is {@code null} for none, and is taken here rather
     * than added afterwards because the empty-group refusal fires at construction, and an index moves its class.
     */
    static FirstFilledOperand group(GroupListRef group, List<FieldRef> expandedFields, boolean unstarredRepetition,
            SemanticIndexKey semanticIndex) {
        return new FirstFilledOperand(null, Objects.requireNonNull(group, "group"), expandedFields,
                unstarredRepetition, null, semanticIndex, false);
    }

    static FirstFilledOperand of(EnumCategoryAccess category) {
        return of(category.field()).withCategory(category.category()).withOuter(category.outer());
    }

    static FirstFilledOperand of(IndexedFieldValue indexed) {
        FirstFilledOperand result = of(indexed.field());
        return switch (indexed) {
            case SemanticIndexValue value -> result.forIndex(value.indexValue());
            case SemanticIndexFieldValue value -> result.forIndex(value.indexField());
        };
    }

    /** Adds the checked category projection represented by {@code category}. */
    public FirstFilledOperand withCategory(CategoryRef category) {
        Objects.requireNonNull(category, "category");
        requireCategoryTarget();
        if (!field.fieldPath().equals(category.field().path().value())) {
            throw new IllegalArgumentException("category " + category.name()
                    + " does not belong to FirstFilledValue target " + renderedPath());
        }
        return withCategory(category.name());
    }

    FirstFilledOperand withCategory(String category) {
        requireCategoryTarget();
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("a FirstFilledValue category must not be blank");
        }
        if (this.category != null) {
            throw new IllegalStateException("FirstFilledValue target already has category " + this.category);
        }
        return copy(category, semanticIndex, outer, field);
    }

    /** Adds a literal semantic-index modifier. */
    public FirstFilledOperand forIndex(String value) {
        return withSemanticIndex(new LiteralIndex(value));
    }

    /** Adds a field-valued semantic-index modifier. */
    public FirstFilledOperand forIndex(FieldRef field) {
        return withSemanticIndex(new FieldIndex(field));
    }

    /**
     * A star beside the index is left to the kernel: A12 admits one below the index group and refuses one on or
     * above it, and this operand does not know which group that is ({@link BoundModel#firstFilledGroupFor}
     * does, and refuses there; {@code validate.laws.SemanticIndexWildcardPlacementLawsTest}).
     */
    private FirstFilledOperand withSemanticIndex(SemanticIndexKey key) {
        if (semanticIndex != null) {
            throw new IllegalStateException("FirstFilledValue target already has a semantic index");
        }
        return copy(category, key, outer, field);
    }

    /** Adds the owner {@code Having} filter to a wildcard field target. */
    public FirstFilledOperand having(Condition filter) {
        if (field == null) {
            // Unlike the other two shape refusals, an unstarred repeatable level does not move this class, so
            // only an index can (validate.laws.FirstFilledOperandShapeLawsTest).
            throw groupShapeRefusal(semanticIndex == null, KernelCode.MVK_NO_GROUPS_ALLOWED,
                    "FirstFilledValue does not accept a Having filter on the group " + renderedPath());
        }
        if (!(field instanceof FieldListRef list)) {
            // Deliberately uncoded: A12's class here depends on context this operand cannot see — MVK_NO_WILDCARD
            // where the field crosses a repeatable level the rule does not iterate, else MVK_WILDCARDS_REQUIRED
            // (KERNEL-FINDINGS KF358, KF424; measured on this carrier in validate.laws.FirstFilledOperandShapeLawsTest).
            throw new IllegalArgumentException("Having applies to a starred FirstFilledValue field target, not "
                    + renderedPath());
        }
        return copy(category, semanticIndex, outer, list.having(filter));
    }

    private void requireCategoryTarget() {
        if (field == null) {
            throw groupShapeRefusal(groupClassIsFixed(), KernelCode.MVK_NO_GROUPS_ALLOWED,
                    "FirstFilledValue does not accept a category projection on the group " + renderedPath());
        }
    }

    /**
     * Whether A12's class for the empty-group and group-category shapes is the shape's own. Three things move it,
     * and the operand is built before the rule and the filter that decide them. A repeatable level left unstarred
     * is bound when the rule iterates it and is a missing star ({@code MVK_NO_WILDCARD}) when it does not, an
     * index answers {@code MVK_NO_INDEX_FIELD} first when it resolves to no index field, and the {@code $} marker
     * is {@code MVK_INVALID_OUTER_ITERATION} outside a filter, though not inside one
     * ({@code validate.laws.FirstFilledOperandShapeLawsTest}).
     */
    private boolean groupClassIsFixed() {
        return !unstarredRepetition && semanticIndex == null && !outer;
    }

    /**
     * Whether A12's read of this operand may answer ahead of the list gates from what the operand cannot settle:
     * an index's resolution, an unstarred repeatable level, which the rule binds or not, and the {@code $} marker,
     * which only a filter admits. Field targets retain their model-resolved repetition provenance (KF458).
     */
    boolean readMayAnswerFirst() {
        return semanticIndex != null || unstarredRepetition || outer
                || field != null && OperandRead.mayRefuse(field);
    }

    /** Whether the target is a root group, which the list's root gate refuses once no duplicate answers first. */
    boolean rootGroup() {
        return group != null && group.path().indexOf('/', 1) < 0;
    }

    /** The shape's own code where nothing can move it, else the same refusal deliberately uncoded. */
    private static IllegalArgumentException groupShapeRefusal(boolean fixed, KernelCode code, String problem) {
        return fixed ? Refusals.of(code, problem) : new IllegalArgumentException(problem);
    }

    /** Marks this operand as the {@code $} outer-iteration reference inside a filter. */
    public FirstFilledOperand outer() {
        return withOuter(true);
    }

    private FirstFilledOperand withOuter(boolean value) {
        return copy(category, semanticIndex, value, field);
    }

    private FirstFilledOperand copy(
            String newCategory, SemanticIndexKey newIndex, boolean newOuter, FieldOperand newField) {
        return new FirstFilledOperand(newField, group, expandedGroupFields, unstarredRepetition,
                newCategory, newIndex, newOuter);
    }

    /** The field target, empty for a group entity. */
    public Optional<FieldOperand> field() {
        return Optional.ofNullable(field);
    }

    /** The group target, empty for a field entity. */
    public Optional<GroupListRef> group() {
        return Optional.ofNullable(group);
    }

    /** Recursively expanded group fields used only for owner result-family validation. */
    public List<FieldRef> expandedGroupFields() {
        return expandedGroupFields;
    }

    public Optional<String> category() {
        return Optional.ofNullable(category);
    }

    public Optional<SemanticIndexKey> semanticIndex() {
        return Optional.ofNullable(semanticIndex);
    }

    public boolean outerReference() {
        return outer;
    }

    public ValueKind kind() {
        if (category != null) {
            return ValueKind.ENUMERATION;
        }
        if (field != null) {
            return field.kind();
        }
        return FirstFilledValue.resultKind(expandedGroupFields.stream()
                .map(FirstFilledOperand::of).toList());
    }

    public String renderedPath() {
        return field != null ? field.renderedPath() : group.renderedPath();
    }

    public boolean expandsToList() {
        return group != null || field instanceof FieldListRef;
    }

    boolean hasWildcard() {
        return field instanceof FieldListRef || group != null && !group.flattened().isEmpty();
    }

    @Override
    public String fieldPath() {
        return field != null ? field.fieldPath() : group.path();
    }

    /**
     * This carrier's operands are richer than the sealed unions' — an owner entity may be a field OR a group,
     * and its semantic index may be keyed by a literal or by a field read. Both fold into the identity, because
     * the same entity under two different keys is two operands and the kernel compares the key.
     */
    @Override
    public Optional<String> exactIdentity() {
        if (hasWildcard()) {
            return Optional.empty();
        }
        return Optional.of((outerReference() ? "$" : "") + fieldPath() + overlapModifier());
    }

    /**
     * Category and semantic index, as the containment arm compares them.
     *
     * <p>Two arms have no reachable input at any locus this carrier can author today, so a mutant emptying
     * either is EXPECTED to survive: a FIELD-keyed index is refused by the semantic-index iteration gate
     * first, and the {@code $} correlation is legal only inside a {@code Having} filter
     * (both measured, {@code validate.laws.DuplicateOverlapCarrierSweepLawsTest}). They are kept because
     * omitting a component the kernel compares is the direction that OVER-refuses if the shape ever becomes
     * authorable — and the iteration code is locus-dependent, so this is not a global unreachability claim.
     */
    @Override
    public String overlapModifier() {
        return category().map(c -> "Cat:" + c).orElse("")
                + semanticIndex().map(key -> switch (key) {
                    case LiteralIndex literal -> "For:\"" + literal.value() + "\"";
                    case FieldIndex fieldKey -> "For:" + (fieldKey.field().correlated() ? "$" : "")
                            + fieldKey.field().renderedPath();
                }).orElse("");
    }

    private static FieldOperand withoutOuter(FieldOperand field) {
        return switch (field) {
            case FieldRef ref -> new FieldRef(ref.path(), ref.kind(), ref.styleHint(), false, ref.repeatingAncestors());
            case FieldListRef list -> new FieldListRef(
                    new FieldRef(list.field().path(), list.field().kind(), list.field().styleHint(), false,
                            list.field().repeatingAncestors()),
                    list.flattened(), list.filter(), false);
            // Provably unreachable: of(FieldOperand)'s switch routes an IndexedFieldValue to
            // of(IndexedFieldValue) before this method is ever called, and withoutOuter has no other
            // caller. Exhaustive anyway because the parameter type is the whole sealed FieldOperand.
            case IndexedFieldValue ignored -> throw new IllegalStateException(
                    "withoutOuter is never called with an IndexedFieldValue — of(FieldOperand) routes"
                            + " that arm to of(IndexedFieldValue) first");
        };
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FirstFilledOperand operand
                && Objects.equals(field, operand.field)
                && Objects.equals(group, operand.group)
                && expandedGroupFields.equals(operand.expandedGroupFields)
                && unstarredRepetition == operand.unstarredRepetition
                && Objects.equals(category, operand.category)
                && Objects.equals(semanticIndex, operand.semanticIndex)
                && outer == operand.outer;
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, group, expandedGroupFields, unstarredRepetition, category, semanticIndex, outer);
    }

    @Override
    public String toString() {
        return "FirstFilledOperand[target=" + renderedPath() + ", category=" + category
                + ", semanticIndex=" + semanticIndex + ", outer=" + outer + "]";
    }
}
