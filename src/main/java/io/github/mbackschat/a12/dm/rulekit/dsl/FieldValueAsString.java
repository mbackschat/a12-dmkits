package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code FieldValueAsString(field)} — a field's value coerced to its string representation (the kernel
 * {@code FieldIdOperationName.FieldValueAsString}). A string-valued {@link Calculation}, so it composes
 * in string comparisons and concatenations. The argument renders as a bare entity reference (like
 * {@code Length(field)}), not the bracketed value form. Build via {@link Dsl#fieldValueAsString}.
 */
public record FieldValueAsString(FieldRef field) implements Calculation {

    @Override
    public ValueKind kind() {
        return ValueKind.STRING;
    }

    public String operatorId() {
        return "FieldValueAsString";
    }
}
