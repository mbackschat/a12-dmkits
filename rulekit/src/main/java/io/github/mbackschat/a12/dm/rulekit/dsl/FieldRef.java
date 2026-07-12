package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * A model-resolved reference to a field — its absolute path plus the {@link ValueKind} read from the
 * model. Carrying the kind is what lets typed handles steer authors away from type-illegal
 * combinations (§5b); carrying the {@link PathStyle} provenance hint is the D14/F10 hook that lets
 * read references remember their authored notation. Obtain handles from {@link BoundModel}
 * (always {@link PathStyle#CANONICAL}); the read converter constructs the hinted form.
 */
public record FieldRef(String path, ValueKind kind, PathStyle styleHint, boolean correlated) implements FieldOperand {

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
     * The one correlation verb shared with the value refs ({@link NumberRef#outer()}, …).
     */
    public FieldRef outer() {
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

    /** {@code FieldFilled([path])} — this field currently holds a value. catalog:FieldFilled */
    public Condition filled() {
        return new FieldPresenceCondition(this, true);
    }

    /** {@code FieldNotFilled([path])} — this field is empty. catalog:FieldNotFilled */
    public Condition notFilled() {
        return new FieldPresenceCondition(this, false);
    }

    /** This field's value as an operand ({@code [path]}; {@code [$path]} if outer-correlated). */
    public FieldValue value() {
        return new FieldValue(this, correlated);
    }

    /**
     * The cell this field's semantic index resolves to, as a presence handle:
     * {@code semanticIndex("SKU-1").filled()} authors {@code FieldFilled([Field For "SKU-1"])}
     * (KERNEL-SEMANTICS §9). The literal-keyed peer of {@link #semanticIndex(FieldRef)}; the cell's VALUE
     * (for an operand slot) is a typed ref's {@code forIndex(key)} ({@link NumberRef#forIndex(String)}) instead. catalog:SemanticIndex
     */
    public SemanticIndex semanticIndex(String indexValue) {
        return new SemanticIndex(new SemanticIndexValue(this, indexValue));
    }

    /**
     * The FIELD-keyed peer of {@link #semanticIndex(String)} — the cell whose index equals the CURRENT value
     * of {@code keyField}: {@code semanticIndex(key).filled()} authors {@code FieldFilled([Field For keyField])}.
     */
    public SemanticIndex semanticIndex(FieldRef keyField) {
        return new SemanticIndex(new SemanticIndexFieldValue(this, keyField));
    }
}
