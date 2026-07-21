package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * A rich, read-only descriptor of one field — the navigation surface's currency (study finding 9,
 * jOOQ-{@code Meta}-style). Exploring is lenient (obtained from streams or the non-throwing
 * {@link BoundModel#fieldAt}); <em>narrowing</em> back to a typed authoring handle
 * ({@link #asNum()}, {@link #asStr()}, …) is strict and keeps every authoring guarantee — it is
 * exactly the corresponding {@link BoundModel} lookup.
 */
public final class FieldInfo {

    private final BoundModel model;
    private final String path;
    private final String name;
    private final ValueKind kind;

    FieldInfo(BoundModel model, String path, String name, ValueKind kind) {
        this.model = model;
        this.path = path;
        this.name = name;
        this.kind = kind;
    }

    public String path() {
        return path;
    }

    /** The short name (last segment, as modeled). */
    public String name() {
        return name;
    }

    public ValueKind kind() {
        return kind;
    }

    /** The containing group's path (every field has one; root groups contain the top-level fields). */
    public String groupPath() {
        return path.substring(0, path.lastIndexOf('/'));
    }

    /** The containing group as a typed handle. */
    public GroupRef group() {
        return model.group(groupPath());
    }

    /** Declared decimal scale; only meaningful when {@link #kind()} is NUMBER (throws otherwise). */
    public int scale() {
        return model.num(path).scale();
    }

    // --- declared field config (read-only): the value constraints the kernel carries on the field type.
    // Each is empty when the field's kind doesn't carry it OR the constraint is unset, so any field can be
    // probed without a kind check — the read peer of the modelkit builder's min/max/minLength/maxLength/pattern.

    /** Declared inclusive lower bound ({@code minimum}); empty when not a number or unset. */
    public java.util.Optional<java.math.BigDecimal> numberMin() {
        return model.index().numberMin(path);
    }

    /** Declared inclusive upper bound ({@code maximum}); empty when not a number or unset. */
    public java.util.Optional<java.math.BigDecimal> numberMax() {
        return model.index().numberMax(path);
    }

    /** Declared minimum string length; empty when not a string or unset. */
    public java.util.OptionalInt minLength() {
        return model.index().stringMinLength(path);
    }

    /** Declared maximum string length; empty when not a string or unset. */
    public java.util.OptionalInt maxLength() {
        return model.index().stringMaxLength(path);
    }

    /** Declared regex pattern; empty when not a string or no pattern. */
    public java.util.Optional<String> pattern() {
        return model.index().stringPattern(path);
    }

    /**
     * The enumeration's allowed stored values in authored order. This is the values-only read surface:
     * it does not materialize category projections.
     *
     * @throws IllegalArgumentException if this is not an enumeration field
     */
    public java.util.List<String> allowedValues() {
        return model.index().enumValues(path);
    }

    // --- annotations: the read peer of EditSession.setAnnotation (study finding 9, annotation reads) --

    /** This field's element-level annotations, in document order; empty (never {@code null}) when none. */
    public java.util.List<Annotation> annotations() {
        return model.annotationsOf(path);
    }

    /** The annotation named {@code name}, if present — the matcher the "find fields by annotation" use case needs. */
    public java.util.Optional<Annotation> annotation(String name) {
        return annotations().stream().filter(a -> a.name().equals(name)).findFirst();
    }

    // --- narrowing: lenient exploring → strict authoring (throws on kind mismatch) -------------

    public FieldRef ref() {
        return model.field(path);
    }

    public NumberRef asNum() {
        return model.num(path);
    }

    public StringRef asStr() {
        return model.str(path);
    }

    public EnumRef asEnum() {
        return model.enumOf(path);
    }

    public BoolRef asBool() {
        return model.bool(path);
    }

    public ConfirmRef asConfirm() {
        return model.confirm(path);
    }

    @Override
    public String toString() {
        return path + " (" + kind + ")";
    }
}
