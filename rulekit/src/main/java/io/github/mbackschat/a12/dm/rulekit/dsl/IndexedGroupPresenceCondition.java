package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * {@code GroupFilled(Group For key)} / {@code GroupNotFilled(Group For key)}: the presence of the row a semantic
 * index selects, the group peer of {@link IndexedPresenceCondition}. A distinct node from
 * {@link GroupPresenceCondition} because its operand is an {@link IndexedGroupValue}, not a {@link GroupRef}.
 * Authored via {@link IndexedGroupValue#filled()} / {@link IndexedGroupValue#notFilled()}.
 *
 * <p>A12 parses {@code GroupFilled(G For k)} into a one-operand {@code AllGroupsFilled} and {@code GroupNotFilled(G
 * For k)} into a one-operand {@code NoGroupFilled}, and refuses the first spelled that way
 * ({@code MVK_PARAMSIZE_INVALID2}, KERNEL-FINDINGS KF351). So the presence spelling is the one this node renders,
 * and the one both kernel nodes read back as.
 */
public record IndexedGroupPresenceCondition(IndexedGroupValue group, boolean filled) implements Condition {

    @Override
    public String operatorId() {
        return filled ? "GroupFilled" : "GroupNotFilled";
    }
}
