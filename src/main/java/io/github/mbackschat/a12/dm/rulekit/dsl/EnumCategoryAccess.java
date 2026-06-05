package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * The enum-category navigation {@code [field -> Category]} — maps the field's enum value to its
 * category value (positional mapping in the model: {@code categories[i].values[i]} pairs with
 * {@code values[i]}). Yields an enumeration-kind value drawn from the category's value domain.
 */
public record EnumCategoryAccess(FieldRef field, String category) implements Calculation {

    /** The catalog operator-id (D14); the {@code ->} symbol is the same in every language. */
    public String operatorId() {
        return "EnumCategory";
    }

    @Override
    public io.github.mbackschat.a12.dm.core.ValueKind kind() {
        return io.github.mbackschat.a12.dm.core.ValueKind.ENUMERATION;
    }
}
