package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * An enumeration value literal — the <em>stored value</em> of an enum (not its display text, §5b).
 * Renders double-quoted, like {@code [Tier] == "PRO"}. A distinct node from {@link StringLiteral} so
 * the operand's enum-ness travels with the AST (useful for explain/F9).
 */
public record EnumValueLiteral(String value) implements Calculation {

    @Override
    public ValueKind kind() {
        return ValueKind.ENUMERATION;
    }
}
