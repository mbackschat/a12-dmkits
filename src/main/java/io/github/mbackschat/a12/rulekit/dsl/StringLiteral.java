package io.github.mbackschat.a12.rulekit.dsl;

/** A string literal operand. Renders double-quoted, with embedded quotes/backslashes escaped. */
public record StringLiteral(String value) implements Calculation {

    @Override
    public io.github.mbackschat.a12.rulekit.model.ValueKind kind() {
        return io.github.mbackschat.a12.rulekit.model.ValueKind.STRING;
    }
}
