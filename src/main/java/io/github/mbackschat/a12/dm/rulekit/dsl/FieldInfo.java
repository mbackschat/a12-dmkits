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
