package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;
import java.util.Optional;

/**
 * The enum-category navigation {@code [field -> Category]} — maps the field's enum value to its
 * category value (positional mapping in the model: {@code categories[i].values[i]} pairs with
 * {@code values[i]}). Yields an enumeration-kind value drawn from the category's value domain.
 *
 * <p>It is both a {@link Calculation} (a comparison operand, rendered {@code [field -> Cat]}) and a
 * {@link ValueListOperand} (a field-list quantifier member, rendered bare {@code field -> Cat}) — the
 * two roles the kernel gives an {@code EntityRef} carrying a category name. It is deliberately NOT a
 * {@link FieldOperand}, so it cannot appear in the slots the kernel rejects a category in
 * (presence quantifiers, aggregates, uniqueness, date-range overlap).
 */
public record EnumCategoryAccess(FieldRef field, String category, boolean outer,
        Optional<FirstFilledOperand.SemanticIndexKey> key)
        implements Calculation, ValueListOperand {

    /**
     * The marker lives on {@link #outer()}, the form the reader builds, so an authored read equals the one read back.
     * The optional key selects the row whose category is read ({@code [field -> Cat For "a"]}, KERNEL-FINDINGS KF568).
     */
    public EnumCategoryAccess {
        outer |= field.correlated();
        field = field.marked(false);
        java.util.Objects.requireNonNull(key, "key");
    }

    /** An unkeyed category access. */
    public EnumCategoryAccess(FieldRef field, String category, boolean outer) {
        this(field, category, outer, Optional.empty());
    }

    /** A non-correlated, unkeyed category access. */
    public EnumCategoryAccess(FieldRef field, String category) {
        this(field, category, false);
    }

    /** The catalog operator-id (D14); the {@code ->} symbol is the same in every language. */
    public String operatorId() {
        return "EnumCategory";
    }

    @Override
    public ValueKind kind() {
        return ValueKind.ENUMERATION;
    }

    /** The projection form {@code field -> Cat} — for diagnostics/F9; the renderer emits the bare quantifier form. */
    @Override
    public String renderedPath() {
        return field.path() + " -> " + category + keySuffix();
    }

    /** A category projection is a distinct entity from a plain read of the same field, so the category
     *  belongs in the identity — the kernel compares {@code categoryName} too. */
    @Override
    public Optional<String> exactIdentity() {
        return Optional.of((outer ? "$" : "") + field.path() + " -> " + category + keySuffix());
    }

    @Override
    public String overlapModifier() {
        return "Cat:" + category + key.map(FirstFilledOperand::keyModifier).orElse("");
    }

    /**
     * The key in the diagnostic form, which the identity keys on as A12 keys a read on its index. A correlated key
     * keeps its {@code $}, as the condition spells it; A12 refuses one wherever a keyed category can stand
     * ({@code dsl.KeyedCategoryTest}), so the mark separates operands no list can yet hold together.
     */
    private String keySuffix() {
        return key.map(k -> switch (k) {
            case FirstFilledOperand.LiteralIndex literal -> " For \"" + literal.value() + "\"";
            case FirstFilledOperand.FieldIndex fieldKey -> " For " + (fieldKey.field().correlated() ? "$" : "")
                    + fieldKey.field().path();
        }).orElse("");
    }

    /** The base field's path — a category projection references its underlying enum field. */
    @Override
    public String fieldPath() {
        return field.path().value();
    }
}
