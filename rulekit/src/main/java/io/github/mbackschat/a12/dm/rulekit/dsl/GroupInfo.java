package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.Optional;

/**
 * A rich, read-only descriptor of one group — the navigation surface's group currency (study
 * finding 9). Narrow to the typed authoring handle via {@link #ref()}.
 */
public final class GroupInfo {

    private final BoundModel model;
    private final String path;
    private final String name;
    private final boolean repeating;

    GroupInfo(BoundModel model, String path, String name, boolean repeating) {
        this.model = model;
        this.path = path;
        this.name = name;
        this.repeating = repeating;
    }

    public String path() {
        return path;
    }

    public String name() {
        return name;
    }

    public boolean repeating() {
        return repeating;
    }

    /** This group's maximum repetitions — the repeatability count ({@code 1} = non-repeatable). */
    public int maxRepetitions() {
        return model.maxRepetitions(path);
    }

    /** This group's template marker, such as {@code attachment} or {@code multi-select}, when declared. */
    public Optional<String> usageType() {
        return model.usageTypeOfGroup(path);
    }

    /** This group as a typed authoring handle. */
    public GroupRef ref() {
        return model.group(path);
    }

    /** This group's element-level annotations, in document order; empty (never {@code null}) when none.
     *  The read peer of {@code GroupEditor.setAnnotation}, symmetric with {@link FieldInfo#annotations()}. */
    public java.util.List<Annotation> annotations() {
        return model.annotationsOfGroup(path);
    }

    /** The annotation named {@code name}, if present. */
    public Optional<Annotation> annotation(String name) {
        return annotations().stream().filter(a -> a.name().equals(name)).findFirst();
    }

    // --- relative navigation: typed refs by path RELATIVE to this group (the read peer of GroupEditor) ---
    //     so a combinator can take a root GroupInfo and apply to any subtree — e.g. a mounted sub-form.

    private String at(String relative) {
        return path + "/" + relative;
    }

    public NumberRef num(String relative) {
        return model.num(at(relative));
    }

    public StringRef str(String relative) {
        return model.str(at(relative));
    }

    public EnumRef enumOf(String relative) {
        return model.enumOf(at(relative));
    }

    public BoolRef bool(String relative) {
        return model.bool(at(relative));
    }

    public ConfirmRef confirm(String relative) {
        return model.confirm(at(relative));
    }

    public DateRef date(String relative) {
        return model.date(at(relative));
    }

    public TimeRef time(String relative) {
        return model.time(at(relative));
    }

    public DateTimeRef dateTime(String relative) {
        return model.dateTime(at(relative));
    }

    public DateFragmentRef dateFragment(String relative) {
        return model.dateFragment(at(relative));
    }

    public DateRangeRef dateRange(String relative) {
        return model.dateRange(at(relative));
    }

    /** An untyped field handle relative to this group. */
    public FieldRef field(String relative) {
        return model.field(at(relative));
    }

    /** A child group handle relative to this group. */
    public GroupRef group(String relative) {
        return model.group(at(relative));
    }

    /**
     * A flattened repetition list relative to this group — the star-aggregate peer of the scalar navigation
     * above. The {@code relativeStarPath} carries its own star on each repeatable segment (e.g. flattening a
     * child {@code Cost} across a repeatable {@code Subs} subgroup); this group's path is prepended, so the
     * call resolves {@code <thisPath>} then the relative portion. Equal to the by-path {@code model.flatten};
     * the read peer of {@link io.github.mbackschat.a12.dm.edit.GroupEditor#flatten(String)}.
     */
    public FieldListRef flatten(String relativeStarPath) {
        return model.flatten(at(relativeStarPath));
    }

    /** A (possibly starred) group scope relative to this group — the group analogue of {@link #flatten(String)}
     *  (for the field-fill group quantifiers). */
    public GroupListRef flattenGroup(String relativeStarPath) {
        return model.flattenGroup(at(relativeStarPath));
    }

    @Override
    public String toString() {
        return path + (repeating ? " (repeating)" : "");
    }
}
