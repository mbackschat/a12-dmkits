package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code NumberOfValueInFields(value IN f1, f2, …)} — the count of the listed fields whose value equals
 * the given constant (the kernel {@code EntityListOperationName.NumberOfValueInFields}, an
 * {@code IEntityListOperationWithValue}). A number-valued {@link Calculation}. The {@code value} is a
 * constant — a boolean ({@code True}), number, string, or date literal — rendered as a value; the fields
 * render as bare entity refs after the {@code In} grammar literal.
 *
 * <p>The kernel requires at least two fields (or at least one group); {@code MVK_PARAMSIZE_INVALIDN}
 * otherwise — that is its call (F4/N7), not pre-checked here. Build via {@link Dsl#numberOfValueInFields}.
 */
public record NumberOfValueInFields(Calculation value, List<FieldOperand> fields) implements Calculation {

    public NumberOfValueInFields {
        fields = List.copyOf(fields);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("NumberOfValueInFields needs at least one field");
        }
    }

    @Override
    public ValueKind kind() {
        return ValueKind.NUMBER;
    }

    public String operatorId() {
        return "NumberOfValueInFields";
    }
}
