package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * A date constant — {@code [DispatchDate] > "01.01.2022"}. The kernel stores date constants in German
 * {@code dd.MM.yyyy} format (with or without the year, e.g. {@code "01.01.2022"} or {@code "13.07."}),
 * independent of the compared field's display format, so the {@code value} is carried verbatim and
 * renders quoted. Like the number/string literals it carries no operator-id — the quote syntax is
 * intrinsic, not a catalog keyword. Wrap in a {@link DateExpr} to compare/shift it as a {@link DateOperand}.
 */
public record DateLiteral(String value) implements Calculation {

    @Override
    public ValueKind kind() {
        return ValueKind.DATE;
    }
}
