package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code RangeAsString(field, start, end)} — a substring of a string field's value, by character range
 * (the kernel {@code FieldIdOperationName.RangeAsString}, an {@code IFieldIdOperationWithParameters}).
 * A string-valued {@link Calculation}; the field renders as a bare entity reference followed by the two
 * range bounds. Build via {@link Dsl#rangeAsString}.
 */
public record RangeAsString(FieldRef field, int start, int end) implements Calculation {

    public RangeAsString {
        if (start < 0 || end < 0) {
            throw new IllegalArgumentException("RangeAsString bounds must be >= 0, was " + start + ", " + end);
        }
    }

    @Override
    public ValueKind kind() {
        return ValueKind.STRING;
    }

    public String operatorId() {
        return "RangeAsString";
    }
}
