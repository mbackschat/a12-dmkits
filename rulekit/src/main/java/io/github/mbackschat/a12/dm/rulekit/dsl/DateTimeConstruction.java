package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code DateTime(date, time)} — the kernel's date-time construction from a date and a time (its
 * {@code IDateTimeConstructionOperation}). A {@link Calculation} of {@link ValueKind#DATE_TIME}; wrap
 * in a {@link DateTimeExpr} to compare it. The {@code date} operand is a date value (a field bare, a
 * date literal quoted, or a {@code Date(...)} construction); the {@code time} operand a time value (a
 * field bare, a time literal quoted, or a {@code Time(...)} construction) — both rendered in argument
 * position.
 */
public record DateTimeConstruction(Calculation date, Calculation time) implements Calculation {

    @Override
    public ValueKind kind() {
        return ValueKind.DATE_TIME;
    }

    public String operatorId() {
        return "DateTime";
    }
}
