package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code FieldValueAsNumber(field)} — a string or enum field's value coerced to a number (the kernel
 * {@code FieldIdOperationName.FieldValueAsNumber}). A number-valued {@link Calculation}; the field
 * renders as a bare entity reference. The kernel requires the selected field values (or category values) to
 * <em>always</em> convert to a number (else {@code MVK_NO_CONVERT_IN_NUMBER}) — that is its call (F4/N7),
 * not pre-checked here. Build via {@link Dsl#fieldValueAsNumber}.
 */
public record FieldValueAsNumber(NumericTextSource source) implements Calculation {

    /** Binary/source-compatible constructor for the original direct-field shape. */
    public FieldValueAsNumber(FieldRef field) {
        this((NumericTextSource) field);
    }

    /** The underlying field, independent of whether {@link #source()} selects a category. */
    public FieldRef field() {
        return source.field();
    }

    @Override
    public ValueKind kind() {
        return ValueKind.NUMBER;
    }

    public String operatorId() {
        return "FieldValueAsNumber";
    }
}
