package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;

/**
 * A group scope for the field-fill quantifiers — "the fields of this group", optionally across all
 * repetitions of one or more starred groups (the group analogue of {@link FieldListRef}). The
 * terminal {@link #group} is the group whose fields are checked; {@link #flattened} lists the starred
 * segments (each a repeatable group), so {@code Categories*}{@code /Values*} keeps <em>both</em> stars
 * on round-trip. A bare group (no stars) has an empty {@code flattened}. Obtain via
 * {@link BoundModel#flattenGroup(String)}.
 */
public record GroupListRef(GroupRef group, List<GroupRef> flattened) {

    public GroupListRef {
        flattened = List.copyOf(flattened);
    }

    /** The terminal group's absolute path (no stars). */
    public String path() {
        return group.path();
    }

    /** The path with a {@code *} re-inserted after each starred segment, e.g. {@code /X/Categories*&#47;Values*}. */
    public String renderedPath() {
        String path = group.path();
        StringBuilder rendered = new StringBuilder();
        int from = 0;
        for (GroupRef starred : flattened) {
            int end = starred.path().length();
            rendered.append(path, from, end).append('*');
            from = end;
        }
        rendered.append(path, from, path.length());
        return rendered.toString();
    }
}
