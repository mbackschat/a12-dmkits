package io.github.mbackschat.a12.dm.rulekit.dsl;

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

    /** This group as a typed authoring handle. */
    public GroupRef ref() {
        return model.group(path);
    }

    @Override
    public String toString() {
        return path + (repeating ? " (repeating)" : "");
    }
}
