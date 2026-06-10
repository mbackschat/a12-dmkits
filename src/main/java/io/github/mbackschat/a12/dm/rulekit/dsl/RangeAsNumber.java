package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code RangeAsNumber(field, start, end)} — a substring of a string field's value, read as a number
 * (the kernel {@code FieldIdOperationName.RangeAsNumber}, an {@code IFieldIdOperationWithParameters}).
 * A number-valued {@link Calculation}; the field renders as a bare entity reference followed by the two
 * 1-based range bounds. Unlike {@link FieldValueAsNumber} it tolerates any string field — a non-numeric
 * substring yields 0 (§the kernel). Build via {@link Dsl#rangeAsNumber}.
 */
public record RangeAsNumber(FieldRef field, int start, int end) implements Calculation {

    public RangeAsNumber {
        if (start < 1 || end < start) {
            throw new IllegalArgumentException("RangeAsNumber bounds are 1-based and must satisfy"
                    + " 0 < start <= end (the kernel rejects others with MVK_INVALID_INTERVAL),"
                    + " was " + start + ", " + end);
        }
    }

    @Override
    public ValueKind kind() {
        return ValueKind.NUMBER;
    }

    public String operatorId() {
        return "RangeAsNumber";
    }
}
