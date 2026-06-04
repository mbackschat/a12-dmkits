package io.github.mbackschat.a12.rulekit.dsl;

import java.util.List;

import io.github.mbackschat.a12.rulekit.model.ValueKind;

/**
 * A flattened repetition list — the {@code Group*}{@code /Field} path syntax (KERNEL-SEMANTICS §9): the
 * values of {@code field} across <em>all</em> repetitions of the starred group(s). Legal only in
 * list slots (see {@link FieldOperand}). Obtain from {@link BoundModel#flatten(String)}, which
 * verifies every starred segment is a repeatable group ({@code MVK_INVALID_WILDCARD} pre-checked).
 */
public record FieldListRef(FieldRef field, List<GroupRef> flattened) implements FieldOperand {

    public FieldListRef {
        flattened = List.copyOf(flattened);
        if (flattened.isEmpty()) {
            throw new IllegalArgumentException("a repetition list needs at least one starred group"
                    + " — for a plain field reference use the field handle");
        }
    }

    @Override
    public String renderedPath() {
        String path = field.path();
        StringBuilder rendered = new StringBuilder();
        int from = 0;
        for (GroupRef group : flattened) {
            int end = group.path().length();
            rendered.append(path, from, end).append('*');
            from = end;
        }
        rendered.append(path, from, path.length());
        return rendered.toString();
    }

    @Override
    public String fieldPath() {
        return field.path();
    }

    @Override
    public ValueKind kind() {
        return field.kind();
    }
}
