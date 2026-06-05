package io.github.mbackschat.a12.dm.rulekit.dsl;

/** {@code Length(field)} — the character length of a string field, as a numeric operand. */
public record LengthOf(FieldRef field) implements Calculation {

    /** The catalog operator-id (D14) — keyword functions resolve their keyword from the catalog. */
    public String operatorId() {
        return "Length";
    }

    @Override
    public io.github.mbackschat.a12.dm.core.ValueKind kind() {
        return io.github.mbackschat.a12.dm.core.ValueKind.NUMBER;
    }
}
