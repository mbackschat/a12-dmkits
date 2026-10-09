package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.Optional;
import java.util.Objects;
import java.util.List;
import java.util.Set;
import io.github.mbackschat.a12.dm.core.EntityPath;
import io.github.mbackschat.a12.dm.core.HasEntityPath;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * A model-resolved reference to a field — its absolute path plus the {@link ValueKind} read from the
 * model. Carrying the kind is what lets typed handles steer authors away from type-illegal
 * combinations (§5b); carrying the {@link PathStyle} provenance hint is the D14/F10 hook that lets a
 * reference choose its own path spelling. Obtain handles from {@link BoundModel} (always
 * {@link PathStyle#CANONICAL}), and call {@link #absolute()} for the one that must not be relativized.
 *
 * <p><b>The read converter does NOT yet produce a hint</b> — corrected 2026-09-22, having claimed it did:
 * {@code adapter.ConditionConverter} builds every reference through {@code BoundModel.field(…)}, so a
 * re-rendered condition is canonicalized rather than preserving the author's notation. Preserving it is
 * the remaining half of F10 and needs the converter to read {@code PathNotationConfig} off the kernel's
 * parse tree; until it does, {@link PathStyle#SHORT} has no producer and the renderer refuses it.
 */
public record FieldRef(EntityPath path, ValueKind kind, PathStyle styleHint, boolean correlated,
        Optional<Set<EntityPath>> repeatingAncestors)
        implements FieldOperand.Cell, NumericTextSource, TemporalComponent, HasEntityPath, ValueAsDate.Source {

    /**
     * Ancestor repetition is a model fact used only to withhold an ungrounded diagnostic class (KF458).
     * An absent set means unknown topology; a present empty set means fixed ancestry.
     */
    public FieldRef {
        repeatingAncestors = repeatingAncestors.map(Set::copyOf);
    }

    /** Compatibility constructor: without a model, repetition topology is unknown. */
    public FieldRef(EntityPath path, ValueKind kind, PathStyle styleHint, boolean correlated) {
        this(path, kind, styleHint, correlated, Optional.empty());
    }

    boolean mayCrossUnstarredRepetition(List<GroupRef> starred) {
        return repeatingAncestors.map(paths -> paths.stream()
                .anyMatch(path -> starred.stream().noneMatch(group -> group.path().equals(path))))
                .orElse(true);
    }

    /**
     * Diagnostic provenance must not change reference identity: equivalent legacy and model-derived
     * references remain redundant operands (LinterTest.modelProvenanceDoesNotHideARedundantLegacyReference).
     */
    @Override
    public final boolean equals(Object other) {
        return other instanceof FieldRef ref && Objects.equals(path, ref.path) && kind == ref.kind
                && styleHint == ref.styleHint && correlated == ref.correlated;
    }

    @Override
    public final int hashCode() {
        return Objects.hash(path, kind, styleHint, correlated);
    }

    /** Its own path, already parsed — this ref stores the segments rather than the text. */
    @Override
    public EntityPath entityPath() {
        return path;
    }

    /** A builder-made reference — no authored style; the renderer decides the path form. */
    public FieldRef(String path, ValueKind kind) {
        this(EntityPath.of(path), kind, PathStyle.CANONICAL, false);
    }

    public FieldRef(String path, ValueKind kind, PathStyle styleHint) {
        this(EntityPath.of(path), kind, styleHint, false);
    }

    /**
     * This reference as the {@code $} outer-iteration correlation ({@code $path}, D13) — for use as a
     * bare operand inside a {@code Having} filter (e.g. {@code NoFieldFilled($a, $b)}). Legal only there.
     * The one correlation verb shared with the value refs ({@link NumberRef#outer()}, …).
     */
    public FieldRef outer() {
        return new FieldRef(path, kind, styleHint, true, repeatingAncestors);
    }

    /**
     * This reference spelled ABSOLUTE, whatever group the surrounding rule renders against — the one
     * per-operand escape from the renderer's path policy (API-FINDINGS AF159).
     *
     * <p>Reach for it when the absolute spelling is the point rather than a preference: a rule authored on a
     * deep group whose aggregate reads more clearly from the root, or a law whose subject IS which form was
     * written. Everything else should stay {@link PathStyle#CANONICAL} and let the renderer choose, because
     * the relative form is the kernel's preferred one and survives a group rename.
     */
    public FieldRef absolute() {
        return new FieldRef(path, kind, PathStyle.ABSOLUTE, correlated, repeatingAncestors);
    }

    @Override
    public FieldRef field() {
        return this;
    }

    @Override
    public String renderedPath() {
        return path.value();
    }

    @Override
    public String fieldPath() {
        return path.value();
    }

    /** A plain field entity: the resolved path, plus {@code $} because a correlated read addresses a
     *  different repetition than an uncorrelated one and the two are distinct operands. */
    @Override
    public Optional<String> exactIdentity() {
        return Optional.of((correlated ? "$" : "") + path.value());
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

    @Override
    public Calculation expression() {
        return value();
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
