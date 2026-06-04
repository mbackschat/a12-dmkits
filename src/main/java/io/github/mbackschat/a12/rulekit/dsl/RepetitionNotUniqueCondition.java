package io.github.mbackschat.a12.rulekit.dsl;

import java.util.List;
import java.util.Optional;

/**
 * {@code RepetitionNotUnique(key1, key2 @From Scope)} — true for a repetition whose (composite) key
 * value occurs more than once within the reference scope (KERNEL-SEMANTICS §9). Kernel slot rules
 * (verified 2026-06-04, KERNEL-FINDINGS §9): keys are plain fields (no groups, no stars — both
 * erased by the {@link FieldRef}-only signature), each key must be repeatable <em>relative to the
 * rule group</em> ({@code MVK_REPEATABLE_GROUP_MISSING}, position-dependent — kernel-delegated),
 * the rule must iterate, and the optional {@code @From} scope group must be repeatable
 * ({@code MVK_GROUP_NOT_REPEATABLE}, pre-checked in {@link #from}).
 */
public record RepetitionNotUniqueCondition(List<FieldRef> keys, Optional<GroupRef> scope)
        implements Condition {

    public RepetitionNotUniqueCondition {
        keys = List.copyOf(keys);
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("RepetitionNotUnique needs at least one key field");
        }
    }

    /** Scopes the uniqueness check to each repetition of {@code reference} ({@code @From}). */
    public RepetitionNotUniqueCondition from(GroupRef reference) {
        if (!reference.repeating()) {
            throw new IllegalArgumentException("the @From reference group must be repeatable: "
                    + reference.path() + " (MVK_GROUP_NOT_REPEATABLE)");
        }
        return new RepetitionNotUniqueCondition(keys, Optional.of(reference));
    }

    @Override
    public String operatorId() {
        return "RepetitionNotUnique";
    }
}
