package io.github.mbackschat.a12.dm.rulekit.dsl;

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
public record GroupListRef(GroupRef group, List<GroupRef> flattened, boolean correlated)
        implements GroupListOperand, AggregateOperand, ValueListOperand {

    public GroupListRef {
        flattened = List.copyOf(flattened);
    }

    /** The uncorrelated form — the common case, and the shape every factory built before markers existed. */
    public GroupListRef(GroupRef group, List<GroupRef> flattened) {
        this(group, flattened, false);
    }

    /**
     * This scope as the {@code $} outer-iteration correlation ({@code $Group}), the group-list twin of
     * {@link FieldListRef#outer()} and {@link FieldRef#outer()}.
     *
     * <p><b>Why the marker lives on the operand rather than on {@link GroupRef}.</b> A group REFERENCE is not
     * correlated anywhere else — the same call {@code GroupPresenceCondition} made for the scalar leaf
     * (API-FINDINGS finding 135). Only the operand position can be, and a list has one position per element:
     * the kernel admits {@code AllGroupsFilled($A, $B)} beside an in-scope conjunct and its verdict does not
     * move with the marker, so a record that cannot hold one makes that legal model UNREADABLE rather than
     * merely unauthorable (DEV-API-GAPS {@code DG49}).
     */
    public GroupListRef outer() {
        return new GroupListRef(group, flattened, true);
    }

    /** The terminal group's absolute path (no stars). */
    public String path() {
        return group.path().value();
    }

    /**
     * {@link ValueListOperand#fieldPath()} — the GROUP's path. The operand references the group; the fields it
     * contributes to the list are the kernel's expansion of it, which is not enumerated here.
     */
    @Override
    public String fieldPath() {
        return path();
    }

    /**
     * The path with a {@code *} re-inserted after each starred segment, e.g. {@code /X/Categories*&#47;Values*}.
     * The {@code $} correlation marker is NOT part of it: the marker is a property of this operand's position
     * and the renderer emits it, exactly as {@link FieldRef#renderedPath()} leaves its own marker to the
     * renderer, so a path used for identity or lookup is unaffected by it.
     */
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
