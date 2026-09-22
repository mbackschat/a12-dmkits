package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/** A string literal operand. Renders double-quoted, with embedded quotes/backslashes escaped. */
public record StringLiteral(String value) implements Calculation {

    @Override
    public ValueKind kind() {
        return ValueKind.STRING;
    }
}
