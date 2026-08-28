package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * A date-time constant — {@code [ScheduledAt] > "01.01.2022T17:00:00"}. The kernel stores a date-time
 * constant in {@code dd.MM.yyyy'T'HH:mm:ss} form, independent of the compared field's display format, so
 * the {@code value} is carried verbatim and renders quoted. Like the date/time literals it carries no
 * operator-id — the quote syntax is intrinsic, not a catalog keyword. Wrap in a {@link DateTimeExpr} to
 * compare it as a {@link DateTimeOperand}.
 */
public record DateTimeLiteral(String value) implements Calculation {

    @Override
    public ValueKind kind() {
        return ValueKind.DATE_TIME;
    }
}
