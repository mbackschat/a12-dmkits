package io.github.mbackschat.a12.rulekit.dsl;

import io.github.mbackschat.a12.rulekit.model.ValueKind;

/**
 * A model-resolved reference to a field — its absolute path plus the {@link ValueKind} read from the
 * model. Carrying the kind is what lets typed handles steer authors away from type-illegal
 * combinations (§5b); carrying the {@link PathStyle} provenance hint is the D14/F10 hook that lets
 * ingested references remember their authored notation. Obtain handles from {@link BoundModel}
 * (always {@link PathStyle#CANONICAL}); the ingest converter constructs the hinted form.
 */
public record FieldRef(String path, ValueKind kind, PathStyle styleHint) {

    /** A builder-made reference — no authored style; the renderer decides the path form. */
    public FieldRef(String path, ValueKind kind) {
        this(path, kind, PathStyle.CANONICAL);
    }

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
