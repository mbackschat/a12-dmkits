package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * {@code GroupFilled(...)} / {@code GroupNotFilled(...)} — whether the group (row) exists/has content.
 *
 * <p>With {@link #outer} set the operand carries the {@code $} correlation marker
 * ({@code GroupFilled($/Order/Billing)}), so the group is resolved from the outer repetition environment a
 * {@code Having} captured rather than from the candidate's own. The flag sits on this node rather than on
 * {@link GroupRef} for the same reason {@link CurrentRepetition} carries it: a group reference is not itself
 * correlated anywhere else, and only the operator position can be.
 *
 * <p><b>The marker is admitted here, and on a bound-but-not-reopened level it is redundant</b> — a group
 * presence leaf reads the captured environment exactly as a field reference does, so both spellings of an
 * out-of-scope leaf draw the same {@code MVK_NO_ITERATION_FOR_WILDCARD} and both are admitted beside an
 * in-scope conjunct (KERNEL-FINDINGS §9; a12-kernel-lean's {@code SPEC-2026-09-04-05}). It is still
 * representable rather than erased, because the kernel accepts it and a model carrying it must stay readable.
 */
public record GroupPresenceCondition(GroupRef group, boolean filled, boolean outer) implements Condition {

    /** An uncorrelated group-presence condition. */
    public GroupPresenceCondition(GroupRef group, boolean filled) {
        this(group, filled, false);
    }

    @Override
    public String operatorId() {
        return filled ? "GroupFilled" : "GroupNotFilled";
    }
}
