package io.github.mbackschat.a12.rulekit.dsl;

/** {@code Length(field)} — the character length of a string field, as a numeric operand. */
public record LengthOf(FieldRef field) implements Calculation {
}
