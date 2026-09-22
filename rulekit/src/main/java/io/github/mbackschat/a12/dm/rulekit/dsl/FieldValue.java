package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.Optional;

/**
 * The field-value operator {@code [path]} — reads a field's value for use inside an expression.
 * {@code outer} marks the {@code $} outer-iteration correlation ({@code [$path]}): a reference that
 * binds to the enclosing rule iteration rather than the row being filtered. It is legal only inside a
 * {@code Having} filter (kernel: {@code MVK_INVALID_OUTER_ITERATION} otherwise, D13); ordinary
 * value reads leave it {@code false}.
 */
public record FieldValue(FieldRef field, boolean outer) implements Calculation, DuplicateOperand {

    /** An ordinary (non-correlated) value read. */
    public FieldValue(FieldRef field) {
        this(field, false);
    }

    @Override
    public String renderedPath() {
        return (outer ? "$" : "") + field.renderedPath();
    }

    @Override
    public String fieldPath() {
        return field.fieldPath();
    }

    /**
     * A value read carries its OWN correlation flag, so {@code [$path]} and {@code [path]} are two operands.
     * This arm exists because a value list admits field references beside literals, and the kernel checks the
     * subject field and the value list as ONE union.
     */
    @Override
    public Optional<String> exactIdentity() {
        return Optional.of((outer ? "$" : "") + field.path().value());
    }

    @Override
    public io.github.mbackschat.a12.dm.core.ValueKind kind() {
        return field.kind();
    }
}
