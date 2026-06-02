package io.github.mbackschat.a12.rulekit.dsl;

import io.github.mbackschat.a12.rulekit.model.ValueKind;

/**
 * A model-resolved reference to a field — its absolute path plus the {@link ValueKind} read from the
 * model. Carrying the kind is what lets typed handles steer authors away from type-illegal
 * combinations (§5b). Obtain handles from {@link BoundModel}.
 */
public record FieldRef(String path, ValueKind kind) {

    public Condition filled() {
        return new FieldPresenceCondition(this, true);
    }

    public Condition notFilled() {
        return new FieldPresenceCondition(this, false);
    }

    /** This field's value as an operand ({@code [path]}). */
    public FieldValue value() {
        return new FieldValue(this);
    }
}
