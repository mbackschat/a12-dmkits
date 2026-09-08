package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.Optional;
import java.util.List;

/**
 * An exact group scope, optionally across all repetitions of one or more starred groups (the group
 * analogue of {@link FieldListRef}). The terminal {@link #group} is the addressed group;
 * {@link #flattened} lists the starred segments (each a repeatable group), so
 * {@code Categories*}{@code /Values*} keeps <em>both</em> stars on round-trip. A bare group (no
 * stars) has an empty {@code flattened}. Group-scoped field-fill quantifiers use it for "the fields
 * of this group"; the legal {@code NoGroupFilled(G*)}/{@code AtLeastOneGroupFilled(G*)} forms use it
 * to scan instantiated group repetitions. Obtain via {@link BoundModel#flattenGroup(String)}.
 */
public record GroupListRef(GroupRef group, List<GroupRef> flattened)
        implements GroupListOperand, AggregateOperand, ValueListOperand {

    public GroupListRef {
        flattened = List.copyOf(flattened);
    }

    /** The terminal group's absolute path (no stars). */
    public String path() {
        return group.path().value();
    }

    /**
     * Exempt from the exact-duplicate arm exactly when this scope RENDERS with a star, which is when it
     * carries flattened segments — the kernel skips a wildcarded ref and lets it occupy several authored
     * slots, so {@code NoGroupFilled(Damages*, Damages*)} is legal. A bare scope renders bare and is not
     * exempt: repeating a non-repeatable group is {@code MVK_DUPLICATE_PARAM1} on both the aggregate and the
     * group-list carrier (KERNEL-FINDINGS {@code kf1015}). Either way the scope still participates in the
     * containment arm, which has no exemption at all.
     */
    @Override
    public Optional<String> exactIdentity() {
        return flattened.isEmpty() ? Optional.of(group.path().value()) : Optional.empty();
    }

    /**
     * {@link DuplicateOperand#fieldPath()} — the GROUP's path. The operand references the group; the fields it
     * contributes to the list are the kernel's expansion of it, which is not enumerated here.
     */
    @Override
    public String fieldPath() {
        return path();
    }

    /** The path with a {@code *} re-inserted after each starred segment, e.g. {@code /X/Categories*&#47;Values*}. */
    public String renderedPath() {
        String path = group.path().value();
        StringBuilder rendered = new StringBuilder();
        int from = 0;
        for (GroupRef starred : flattened) {
            int end = starred.path().value().length();
            rendered.append(path, from, end).append('*');
            from = end;
        }
        rendered.append(path, from, path.length());
        return rendered.toString();
    }
}
