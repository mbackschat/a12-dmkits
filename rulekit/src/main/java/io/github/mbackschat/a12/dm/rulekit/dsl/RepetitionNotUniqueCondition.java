package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;
import java.util.Optional;
import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.core.Refusals;

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
        // Only the exact arm applies, and by signature rather than by choice: List<FieldRef> erases the
        // star that would exempt an operand, admits no category or index, and a field's path is a strict
        // prefix of nothing (MVK_DUPLICATE_PARAM1, KERNEL-FINDINGS kf439 and kf186).
        try {
            DuplicateGate.rejectExactDuplicates("RepetitionNotUnique", keys);
        } catch (KernelCodeRefusal refusal) {
            throw OperandRead.citedRepetitionKeys(refusal, keys);
        }
    }


    /** Scopes the uniqueness check to each repetition of {@code reference} ({@code @From}). catalog:FromScope */
    public RepetitionNotUniqueCondition from(GroupRef reference) {
        if (!reference.repeating()) {
            // Withheld: A12 reads the scope before it asks whether the scope repeats, so a group holding no field
            // answers MVK_GROUP_IS_EMPTY first, and a GroupRef does not show whether it holds one
            // (validate.laws.EntityListQuantifierReadLawsTest).
            throw Refusals.of(KernelCode.MVK_GROUP_NOT_REPEATABLE, "the @From reference group must be repeatable: "
                    + reference.path()).withheld();
        }
        return new RepetitionNotUniqueCondition(keys, Optional.of(reference));
    }

    @Override
    public String operatorId() {
        return "RepetitionNotUnique";
    }
}
