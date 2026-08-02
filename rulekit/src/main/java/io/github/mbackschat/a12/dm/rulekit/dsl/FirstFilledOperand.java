package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * One A12 {@code entitySpec} operand of {@code FirstFilledValue}. The owner shape is compositional: one
 * field or group target may additionally carry a category projection, a semantic index, a wildcard filter,
 * and outer-iteration correlation. Keeping those modifiers on one immutable value prevents one modifier
 * from erasing another during read-back or rendering.
 */
public final class FirstFilledOperand {

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
    private final String category;
    private final SemanticIndexKey semanticIndex;
    private final boolean outer;

    private FirstFilledOperand(
            FieldOperand field,
            GroupListRef group,
            List<FieldRef> expandedGroupFields,
            String category,
            SemanticIndexKey semanticIndex,
            boolean outer) {
        if ((field == null) == (group == null)) {
            throw new IllegalArgumentException("a FirstFilledValue operand needs exactly one field or group target");
        }
        this.field = field;
        this.group = group;
        this.expandedGroupFields = List.copyOf(expandedGroupFields);
        if (group != null && this.expandedGroupFields.isEmpty()) {
            throw new IllegalArgumentException("FirstFilledValue group " + group.renderedPath()
                    + " must contain at least one field");
        }
        this.category = category;
        this.semanticIndex = semanticIndex;
        this.outer = outer;
    }

    /** A field or flattened field-list owner entity. */
    public static FirstFilledOperand of(FieldOperand field) {
        Objects.requireNonNull(field, "field");
        boolean outer = switch (field) {
            case FieldRef ref -> ref.correlated();
            case FieldListRef list -> list.correlated();
        };
        return new FirstFilledOperand(withoutOuter(field), null, List.of(), null, null, outer);
    }

    static FirstFilledOperand group(GroupListRef group, List<FieldRef> expandedFields) {
        return new FirstFilledOperand(null, Objects.requireNonNull(group, "group"), expandedFields,
                null, null, false);
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
        if (field == null || !field.fieldPath().equals(category.field().path())) {
            throw new IllegalArgumentException("category " + category.name()
                    + " does not belong to FirstFilledValue target " + renderedPath());
        }
        return withCategory(category.name());
    }

    FirstFilledOperand withCategory(String category) {
        if (field == null) {
            throw new IllegalArgumentException("FirstFilledValue does not accept a category projection on a group");
        }
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

    private FirstFilledOperand withSemanticIndex(SemanticIndexKey key) {
        if (hasWildcard()) {
            throw new IllegalArgumentException("a semantic index and wildcard may not address the same entity"
                    + " (MVK_SEMANTIC_INDEX_AND_WILDCARD): " + renderedPath());
        }
        if (semanticIndex != null) {
            throw new IllegalStateException("FirstFilledValue target already has a semantic index");
        }
        return copy(category, key, outer, field);
    }

    /** Adds the owner {@code Having} filter to a wildcard field target. */
    public FirstFilledOperand having(Condition filter) {
        if (!(field instanceof FieldListRef list)) {
            throw new IllegalArgumentException("Having applies to a starred FirstFilledValue field target");
        }
        return copy(category, semanticIndex, outer, list.having(filter));
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
        return new FirstFilledOperand(newField, group, expandedGroupFields,
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

    String identityPath() {
        return field != null ? field.fieldPath() : group.path();
    }

    private static FieldOperand withoutOuter(FieldOperand field) {
        return switch (field) {
            case FieldRef ref -> new FieldRef(ref.path(), ref.kind(), ref.styleHint(), false);
            case FieldListRef list -> new FieldListRef(
                    new FieldRef(list.field().path(), list.field().kind(), list.field().styleHint(), false),
                    list.flattened(), list.filter(), false);
        };
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FirstFilledOperand operand
                && Objects.equals(field, operand.field)
                && Objects.equals(group, operand.group)
                && expandedGroupFields.equals(operand.expandedGroupFields)
                && Objects.equals(category, operand.category)
                && Objects.equals(semanticIndex, operand.semanticIndex)
                && outer == operand.outer;
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, group, expandedGroupFields, category, semanticIndex, outer);
    }

    @Override
    public String toString() {
        return "FirstFilledOperand[target=" + renderedPath() + ", category=" + category
                + ", semanticIndex=" + semanticIndex + ", outer=" + outer + "]";
    }
}
