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
public record GroupListRef(GroupRef group, List<GroupRef> flattened, boolean correlated)
        implements GroupListOperand, GroupScope, ValueListOperand {

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

    /**
     * The row of this scope whose index field holds [indexValue], {@code Group For "key"}: the group peer of a
     * field handle's {@code semanticIndex(key)}. A star below the index group stays on the scope, which A12
     * admits ({@code validate.laws.SemanticIndexWildcardPlacementLawsTest}).
     */
    public IndexedGroupValue forIndex(String indexValue) {
        return new GroupSemanticIndexValue(this, indexValue);
    }

    /** The row whose index field holds the value [keyField] holds at run time, {@code Group For KeyField}. */
    public IndexedGroupValue forIndex(FieldRef keyField) {
        return new GroupSemanticIndexFieldValue(this, keyField);
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
     * group-list carrier (KERNEL-FINDINGS {@code kf439}). Either way the scope still participates in the
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
