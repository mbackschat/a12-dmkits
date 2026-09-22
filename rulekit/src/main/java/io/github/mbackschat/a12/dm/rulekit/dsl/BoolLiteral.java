package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * The boolean constants {@code True} / {@code False}. Unlike number/string literals these are
 * <em>localized keywords</em> ({@code Wahr}/{@code Falsch} in DE), so the node carries a catalog
 * operator-id (D14) and the renderer looks the keyword up like any other operator.
 */
public record BoolLiteral(boolean value) implements Calculation {

    /** The catalog operator-id — {@code True} or {@code False}. */
    public String operatorId() {
        return value ? "True" : "False";
    }

    @Override
    public ValueKind kind() {
        return ValueKind.BOOLEAN;
    }
}
