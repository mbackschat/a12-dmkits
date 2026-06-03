package io.github.mbackschat.a12.rulekit.dsl;

/** The field-value operator {@code [path]} — reads a field's value for use inside an expression. */
public record FieldValue(FieldRef field) implements Calculation {

    @Override
    public io.github.mbackschat.a12.rulekit.model.ValueKind kind() {
        return field.kind();
    }
}
