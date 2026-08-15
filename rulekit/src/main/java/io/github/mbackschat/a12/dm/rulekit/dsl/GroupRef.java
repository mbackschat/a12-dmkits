package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.HasEntityPath;

/**
 * A model-resolved reference to a group — its absolute path plus whether it repeats (read from the
 * model, §5a). Group and field references are distinct types, so {@code GroupFilled(field)}
 * ({@code MVK_NO_FIELDS_ALLOWED}) and {@code FieldFilled(group)} ({@code MVK_NO_GROUPS_ALLOWED})
 * are unrepresentable. Obtain from {@link BoundModel#group(String)}.
 *
 * <p>Semantics worth knowing (kernel-verified): for a repeatable group, {@code GroupFilled} means
 * "the repeat row <em>exists</em>", not "the row has data" — an empty, freshly created row counts
 * as filled. And an enclosing {@code GroupFilled(Group)} counts as referencing the rule's error
 * field (no {@code MVK_ERROR_FIELD_NOT_REFERENCED}).
 */
public record GroupRef(String path, boolean repeating) implements GroupReference, HasEntityPath {

    /** Identity — a {@code GroupRef} is its own {@link GroupReference}. */
    @Override
    public GroupRef ref() {
        return this;
    }

    /** {@code GroupFilled(path)}. catalog:GroupFilled */
    public Condition filled() {
        return new GroupPresenceCondition(this, true);
    }

    /** {@code GroupNotFilled(path)} — negative conditions on repeatable groups need guarding (§5). catalog:GroupNotFilled */
    public Condition notFilled() {
        return new GroupPresenceCondition(this, false);
    }
}
