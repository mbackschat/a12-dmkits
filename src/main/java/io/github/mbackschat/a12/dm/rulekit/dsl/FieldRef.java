package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * A model-resolved reference to a field — its absolute path plus the {@link ValueKind} read from the
 * model. Carrying the kind is what lets typed handles steer authors away from type-illegal
 * combinations (§5b); carrying the {@link PathStyle} provenance hint is the D14/F10 hook that lets
 * read references remember their authored notation. Obtain handles from {@link BoundModel}
 * (always {@link PathStyle#CANONICAL}); the read converter constructs the hinted form.
 */
public record FieldRef(String path, ValueKind kind, PathStyle styleHint, boolean outer) implements FieldOperand {

    /** A builder-made reference — no authored style; the renderer decides the path form. */
    public FieldRef(String path, ValueKind kind) {
        this(path, kind, PathStyle.CANONICAL, false);
    }

    public FieldRef(String path, ValueKind kind, PathStyle styleHint) {
        this(path, kind, styleHint, false);
    }

    /**
     * This reference as the {@code $} outer-iteration correlation ({@code $path}, D13) — for use as a
     * bare operand inside a {@code Having} filter (e.g. {@code NoFieldFilled($a, $b)}). Legal only there.
     */
    public FieldRef asOuter() {
        return new FieldRef(path, kind, styleHint, true);
    }

    @Override
    public String renderedPath() {
        return path;
    }

    @Override
    public String fieldPath() {
        return path;
    }

    public Condition filled() {
        return new FieldPresenceCondition(this, true);
    }

    public Condition notFilled() {
        return new FieldPresenceCondition(this, false);
    }

    /** This field's value as an operand ({@code [path]}; {@code [$path]} if outer-correlated). */
    public FieldValue value() {
        return new FieldValue(this, outer);
    }
}
