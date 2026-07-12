package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code FieldValueAsNumber(field)} — a string or enum field's value coerced to a number (the kernel
 * {@code FieldIdOperationName.FieldValueAsNumber}). A number-valued {@link Calculation}; the field
 * renders as a bare entity reference. The kernel requires the field's values (or category values) to
 * <em>always</em> convert to a number (else {@code MVK_NO_CONVERT_IN_NUMBER}) — that is its call (F4/N7),
 * not pre-checked here. Build via {@link Dsl#fieldValueAsNumber}.
 */
public record FieldValueAsNumber(FieldRef field) implements Calculation {

    @Override
    public ValueKind kind() {
        return ValueKind.NUMBER;
    }

    public String operatorId() {
        return "FieldValueAsNumber";
    }
}
