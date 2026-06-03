package io.github.mbackschat.a12.rulekit.dsl;

/**
 * An enumeration value literal — the <em>stored value</em> of an enum (not its display text, §5b).
 * Renders double-quoted, like {@code [Tier] == "PRO"}. A distinct node from {@link StringLiteral} so
 * the operand's enum-ness travels with the AST (useful for explain/F9).
 */
public record EnumValueLiteral(String value) implements Calculation {

    @Override
    public io.github.mbackschat.a12.rulekit.model.ValueKind kind() {
        return io.github.mbackschat.a12.rulekit.model.ValueKind.ENUMERATION;
    }
}
